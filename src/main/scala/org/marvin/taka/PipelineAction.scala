package org.marvin.taka

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import org.marvin.model.EngineMetadata
import org.marvin.taka.ActionHandler.ExecuteBatch
import org.marvin.taka.BatchAction.{BatchExecute, BatchReload}
import org.marvin.taka.PipelineAction.PipelineExecute

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, Future}

object PipelineAction {
  case class PipelineExecute(protocol:String, params:String)
}

class PipelineAction(metadata: EngineMetadata) extends Actor with ActorLogging{
  implicit val ec = context.dispatcher

  var artifactSaver: ActorRef = _

  override def preStart() = {
    artifactSaver = context.actorOf(Props(new ArtifactSaver(metadata)), name = "artifactSaver")
  }

  override def receive  = {
    case PipelineExecute(protocol, params) =>
      implicit val futureTimeout = Timeout(200 seconds)

      log.info(s"Starting to process pipeline process with. Protocol: [$protocol] and Params: [$params].")
      var batchActionHandler: ActorRef = context.actorOf(Props(new BatchActionProxy(metadata.actionsMap("acquisitor"))), name = "batchActionHandler")
      Await.result((batchActionHandler ? ExecuteBatch(protocol, params)), futureTimeout.duration)
      Await.result((batchActionHandler ? Reload(protocol, params)), futureTimeout.duration)


    case Done =>
      log.info("Work Done!")

    case _ =>
      log.warning(s"Not valid message !!")

  }
}