/**
  * Copyright [2017] [B2W Digital]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
  */
package org.marvin.executor.actions

import java.util.concurrent.Executors

import akka.Done
import akka.actor.SupervisorStrategy.{Restart, Resume, Stop}
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import org.marvin.executor.actions.ActionHandler.BatchType
import org.marvin.executor.actions.BatchAction.{BatchHealthCheckMessage, BatchMessage, BatchPipelineMessage, BatchReloadMessage}
import org.marvin.manager.ArtifactSaver
import org.marvin.manager.ArtifactSaver.SaverMessage
import org.marvin.model.EngineMetadata

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object BatchAction {
  case class BatchMessage(actionName:String, params:String, protocol:String)
  case class BatchReloadMessage(actionName: String, artifacts:String, protocol:String)
  case class BatchHealthCheckMessage(actionName: String, artifacts: String)
  case class BatchPipelineMessage(actions:List[String], params:String, protocol:String)
}

class BatchAction(engineMetadata: EngineMetadata) extends Actor with ActorLogging {
  var actionHandler: ActionHandler = _
  val artifactSaveActor = context.actorOf(Props(new ArtifactSaver(engineMetadata)), name = "artifactSaveActor")
  implicit val batchTimeout = Timeout(30 days)  //TODO how to measure???

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 minute) {
      case _: Exception => Stop
      case _: Error => Stop
    }

  override def preStart() = {
    log.info(s"${this.getClass().getCanonicalName} actor initialized...")
    this.actionHandler = new ActionHandler(engineMetadata, BatchType)
  }

  def receive = {
    case BatchMessage(actionName, params, protocol) =>
      implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
      log.info(s"Starting to process batch message to $actionName received with [$protocol]...")

      try{
        log.info(s"Sending message to $actionName ")
        val handlerResponse:String = this.actionHandler.send_message(actionName=actionName, params=params)
        log.info(s"Message [$handlerResponse] return from handler!!")

        log.info(s"Sending message to save [${actionName}] artifacts")
        (artifactSaveActor ? SaverMessage(actionName=actionName, protocol=protocol)).onComplete {

          case Success(result) =>
            log.info(s"Batch message to $actionName with protocol [$protocol] completed with [$result]!!")
            context.parent ! result

          case Failure(failure) =>
            log.error(s"Batch message to $actionName with protocol [$protocol] completed with [$failure]!!")
            context.parent ! Failure
        }

      }catch{
        case e:Exception => e.printStackTrace()
          context.parent ! Failure
      }

    case BatchReloadMessage(actionName, artifacts, protocol) =>
      log.info(s"Sending the message to reload the $artifacts of $actionName using protocol $protocol")
      this.actionHandler.reload(actionName, artifacts, protocol)
      sender ! Done

    case BatchHealthCheckMessage(actionName, artifacts) =>
      log.info(s"Sending message to batch health check. Following artifacts included: $artifacts.")
      sender ! this.actionHandler.healthCheck(actionName, artifacts)

    case BatchPipelineMessage(actions, params, protocol) =>
      //Call all batch actions in order to save and reload the next step
      log.info(s"Executing Pipeline process with...")
      for(actionName <- actions) {
        val artifacts = engineMetadata.actionsMap(actionName).artifactsToLoad.mkString(",")

        if (!artifacts.isEmpty) self ? BatchReloadMessage(actionName, artifacts, protocol)

        self ? BatchMessage(actionName, params, protocol)
      }

      sender ! Done
    
    case Done =>
      log.info("Work done with success!!")

    case _ =>
      log.warning("Received a bad format message...")
  }
}