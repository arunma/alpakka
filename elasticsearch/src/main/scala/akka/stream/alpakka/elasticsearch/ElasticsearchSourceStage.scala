/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.elasticsearch

import java.io.ByteArrayOutputStream

import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}
import org.apache.http.entity.StringEntity
import org.elasticsearch.client.{Response, ResponseListener, RestClient}
import spray.json._
import DefaultJsonProtocol._
import akka.stream.alpakka.elasticsearch.scaladsl.ElasticsearchSourceSettings
import org.apache.http.message.BasicHeader

import scala.collection.JavaConverters._

final case class OutgoingMessage[T](id: String, source: T, version: Option[Long])

case class ScrollResponse[T](error: Option[String], result: Option[ScrollResult[T]])
/* TODO Review
1. Are aggregated results of type T?
2. What if the user would like to get the Hits (size >0) and Aggregates?
3. Is adding aggregate results as a separate item to ScrollResult a good idea? Or should we set the size as 0 for aggregates by default
and have the `T` represent a single aggregate or bucket (considering the aggregated results doesn't fall into the same pattern as `hits`, this would be a little tricky too.
 */
case class ScrollResult[T](scrollId: String, messages: Seq[OutgoingMessage[T]], aggregate: Option[T] = None)

trait MessageReader[T] {
  def convert(json: String): ScrollResponse[T]
}

final class ElasticsearchSourceStage[T](indexName: String,
                                        typeName: Option[String],
                                        searchParams: Map[String, String],
                                        client: RestClient,
                                        settings: ElasticsearchSourceSettings,
                                        reader: MessageReader[T])
    extends GraphStage[SourceShape[OutgoingMessage[T]]] {

  val out: Outlet[OutgoingMessage[T]] = Outlet("ElasticsearchSource.out")
  override val shape: SourceShape[OutgoingMessage[T]] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new ElasticsearchSourceLogic[T](indexName, typeName, searchParams, client, settings, out, shape, reader)

}

sealed class ElasticsearchSourceLogic[T](indexName: String,
                                         typeName: Option[String],
                                         var searchParams: Map[String, String],
                                         client: RestClient,
                                         settings: ElasticsearchSourceSettings,
                                         out: Outlet[OutgoingMessage[T]],
                                         shape: SourceShape[OutgoingMessage[T]],
                                         reader: MessageReader[T])
    extends GraphStageLogic(shape)
    with ResponseListener
    with OutHandler
    with StageLogging {

  private var scrollId: String = null
  private val responseHandler = getAsyncCallback[Response](handleResponse)
  private val failureHandler = getAsyncCallback[Throwable](handleFailure)

  private var waitingForElasticData = false
  private var pullIsWaitingForData = false
  private var dataReady: Option[ScrollResponse[T]] = None

  def sendScrollScanRequest(): Unit =
    try {
      waitingForElasticData = true

      if (scrollId == null) {
        log.debug("Doing initial search")

        // Add extra params to search
        if (!searchParams.contains("size")) {
          searchParams = searchParams + ("size" -> settings.bufferSize.toString)
        }
        if (!searchParams.contains("version") && settings.includeDocumentVersion) {
          // Tell elastic to return the documents '_version'-property with the search-results
          // http://nocf-www.elastic.co/guide/en/elasticsearch/reference/current/search-request-version.html
          // https://www.elastic.co/guide/en/elasticsearch/guide/current/optimistic-concurrency-control.html

          searchParams = searchParams + ("version" -> "true")
        }

        val searchBody = "{" + searchParams
          .map { e =>
            val name = e._1
            val json = e._2
            "\"" + name + "\":" + json
          }
          .mkString(",") + "}"

        val endpoint: String = (indexName, typeName) match {
          case (i, Some(t)) => s"/$i/$t/_search"
          case (i, None) => s"/$i/_search"
        }
        client.performRequestAsync(
          "POST",
          endpoint,
          Map("scroll" -> "5m", "sort" -> "_doc").asJava,
          new StringEntity(searchBody),
          this,
          new BasicHeader("Content-Type", "application/json")
        )
      } else {
        log.debug("Fetching next scroll")

        client.performRequestAsync(
          "POST",
          s"/_search/scroll",
          Map[String, String]().asJava,
          new StringEntity(Map("scroll" -> "5m", "scroll_id" -> scrollId).toJson.toString),
          this,
          new BasicHeader("Content-Type", "application/json")
        )
      }
    } catch {
      case ex: Exception => handleFailure(ex)
    }

  override def onFailure(exception: Exception) = failureHandler.invoke(exception)
  override def onSuccess(response: Response) = responseHandler.invoke(response)

  def handleFailure(ex: Throwable): Unit = {
    waitingForElasticData = false
    failStage(ex)
  }

  def handleResponse(res: Response): Unit = {
    waitingForElasticData = false
    val json = {
      val out = new ByteArrayOutputStream()
      try {
        res.getEntity.writeTo(out)
        new String(out.toByteArray, "UTF-8")
      } finally {
        out.close()
      }
    }

    val scrollResponse = reader.convert(json)

    if (pullIsWaitingForData) {
      log.debug("Received data from elastic. Downstream has already called pull and is waiting for data")
      pullIsWaitingForData = false
      if (handleScrollResponse(scrollResponse)) {
        // we should go and get more data
        sendScrollScanRequest()
      }
    } else {
      log.debug("Received data from elastic. Downstream have not yet asked for it")
      // This is a prefetch of data which we received before downstream has asked for it
      dataReady = Some(scrollResponse)
    }

  }

  // Returns true if we should continue to work
  def handleScrollResponse(scrollResponse: ScrollResponse[T]): Boolean =
    scrollResponse match {
      case ScrollResponse(Some(error), _) =>
        failStage(new IllegalStateException(error))
        false
      /*
      TODO Review This has to be mapped for push
      Also, the isDefined and get looks super ugly. This would be changed once we have a clarity over how to return aggregated results
       */
      case ScrollResponse(None, Some(result)) if result.messages.isEmpty && result.aggregate.isDefined =>
        scrollId = result.scrollId
        emit(out, OutgoingMessage(scrollId, result.aggregate.get, None))
        true
      case ScrollResponse(None, Some(result)) if result.messages.isEmpty =>
        completeStage()
        false
      case ScrollResponse(_, Some(result)) =>
        scrollId = result.scrollId
        log.debug("Pushing data downstream")
        emitMultiple(out, result.messages.toIterator)
        true
    }

  setHandler(out, this)

  override def onPull(): Unit =
    dataReady match {
      case Some(data) =>
        // We already have data ready
        log.debug("Downstream is pulling data and we already have data ready")
        if (handleScrollResponse(data)) {
          // We should go and get more data

          dataReady = None

          if (!waitingForElasticData) {
            sendScrollScanRequest()
          }

        }
      case None =>
        if (pullIsWaitingForData) throw new Exception("This should not happen: Downstream is pulling more than once")
        pullIsWaitingForData = true

        if (!waitingForElasticData) {
          log.debug("Downstream is pulling data. We must go and get it")
          sendScrollScanRequest()
        } else {
          log.debug("Downstream is pulling data. Already waiting for data")
        }
    }

}
