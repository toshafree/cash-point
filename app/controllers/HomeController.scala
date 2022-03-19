package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsNumber, JsString, JsValue, Json, JsObject}
import play.api.libs.ws.WSClient

import scala.concurrent._
import ExecutionContext.Implicits.global

import scala.concurrent.duration._
import akka.actor._
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._

import scala.util.Random


@Singleton
class HomeController @Inject()(implicit system: ActorSystem, val ws:WSClient, val controllerComponents: ControllerComponents) extends BaseController {

  private var webClientId = 0
  private val bankActor = system.actorOf(Props(classOf[BankActor],ws), "bankActor")
  private val cashMachineFatherActor = system.actorOf(Props(classOf[CashPointFatherActor], bankActor), "cashMachineFatherActor")
  private val webClientFatherActor = system.actorOf(Props(classOf[WebClientFatherActor], cashMachineFatherActor), "webClientFatherActor")

  def socket = WebSocket.accept[String, String] { request =>
    val (wsSink, wsSource) = MergeHub.source[String].toMat(BroadcastHub.sink[String])(Keep.both).run()
    webClientId += 1
    system.actorOf(Props(classOf[WebClientActor],
      Source.queue[String](Int.MaxValue, OverflowStrategy.backpressure).toMat(wsSink)(Keep.left).run(),
      webClientFatherActor, webClientId), webClientId.toString)
    Flow.fromSinkAndSource(wsSink, wsSource)
  }

  system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, bankActor, SendUpdate(cashMachineFatherActor))

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }
}


sealed trait CashPointFatherActorMessage
case class BankUpdated(points: List[PointData]) extends CashPointFatherActorMessage
case class ClientJoined(client_id: Int) extends CashPointFatherActorMessage
case class Start(webClientFather: ActorRef) extends CashPointFatherActorMessage

class CashPointFatherActor( bankActor: ActorRef) extends Actor {
  private val cashPoints = new scala.collection.mutable.HashMap[String, ActorRef]
  private var webClientFatherActor: ActorRef = null

  def receive = {
    case Start(webClientFather) =>
      webClientFatherActor = webClientFather
      bankActor ! SendUpdate(self)
    case BankUpdated(points) =>
      points.foreach {
        case p if cashPoints contains p.id => cashPoints(p.id) ! Update(p)
        case p if !(cashPoints contains p.id) =>
          cashPoints(p.id) = context.actorOf(
            Props(new CashPointActor(p, webClientFatherActor)), p.id)
        case _ => null
      }
    case ClientJoined(client_id) =>
      cashPoints.foreach { case (id, ref)  => ref ! GreetNewClient(client_id) }
  }
}


sealed trait CashPointActorMessage
case class Update(point: PointData) extends CashPointActorMessage
case class GreetNewClient(client_id: Int) extends CashPointActorMessage

class CashPointActor(p: PointData, webClientFatherActor: ActorRef) extends Actor {
  var data: PointData = p
  if (data.dollars>0) {
    webClientFatherActor ! StateUpdated(Common.packState(data,"new"))
  }

  def receive = {
    case Update(point: PointData) =>
        val d = data.dollars
        data = point
        d match {
          case 0 if data.dollars > 0 =>
            webClientFatherActor ! StateUpdated(Common.packState(data,"filledUp"))
          case d if d > 0 && data.dollars == 0 =>
            webClientFatherActor ! StateUpdated(Common.packState(data,"getEmpty"))
          case d if d > 0 && data.dollars > 0 && d != data.dollars =>
            webClientFatherActor ! StateUpdated(Common.packState(data,"change"))
          case _ => "empty" // hmm, but break up if you removed it
        }
    case GreetNewClient(client_id) =>
      if (data.dollars>0) {
        webClientFatherActor ! SendStateToNewClient(client_id, Common.packState(data,"new"))
      }
  }
}


sealed trait BankActorMessage
case class SendUpdate(to: ActorRef) extends BankActorMessage

class BankActor(ws: WSClient) extends Actor {

  def receive = {
    case SendUpdate(to) =>
      val data = Json.parse("""{"bounds":{"bottomLeft":{"lat":55.74928446137721,"lng":37.370475669708085},"topRight":{"lat":55.83052807015812,"lng":37.59603871902447}},"filters":{"banks":["tcs"],"showUnavailable":true},"zoom":13}""")
      ws.url("https://api.tinkoff.ru/geo/withdraw/clusters")
        .addHttpHeaders("Content-Type" -> "application/json")
        .post(data).map {
        r => {
          to ! BankUpdated(Common.getPoints(r.body))
        }
      }
  }
}


sealed trait WebClientActorMessage
case class UpdateState(state: JsValue) extends WebClientActorMessage

class WebClientActor(queue: SourceQueueWithComplete[String], webClientFatherActor: ActorRef, id: Int) extends Actor{

  webClientFatherActor ! Joined(id, self)

  def receive = {
    case UpdateState(state: JsObject)=> queue.offer(Json.stringify(state))
  }

  override def postStop(): Unit = {
    super.postStop()
    webClientFatherActor ! Left(id)
    // TODO change to streams event of closing socket with remove Actor.
  }
}


sealed trait WebClientFatherActorMessage
case class SendStateToNewClient(client_id: Int, state: JsValue) extends WebClientFatherActorMessage
case class StateUpdated(state: JsValue) extends WebClientFatherActorMessage
case class Left(client_id: Int) extends WebClientFatherActorMessage
case class Joined(client_id: Int, ref: ActorRef) extends WebClientFatherActorMessage

class WebClientFatherActor(cashPointFatherActor: ActorRef) extends Actor {

  private val webClients = new scala.collection.mutable.HashMap[Int, ActorRef]
  cashPointFatherActor ! Start(self)

  def receive = {
    case Joined(id, ref) =>
      webClients(id) = ref
      cashPointFatherActor ! ClientJoined(id)
    case Left(id) =>
      webClients -= id
    case StateUpdated(state) =>
      webClients.foreach {
        case (i, r) => r ! UpdateState(state)
      }
    case SendStateToNewClient(id, state) =>
      webClients(id) ! UpdateState(state)
  }
}


case class PointData(id: String, address: String, place: String, dollars: Int)

object Common {
  def getPoints(payload: String) = {
    (Json.parse(payload) \ "payload" \ "clusters").as[List[JsObject]].flatMap(a => (a \ "points")
      .as[List[JsObject]])
      .map { case point => PointData(
        id=(point \ "id").as[String],
        address = (point \ "address").as[String],
        place = (point \ "installPlace").as[String],
        dollars = (point \ "limits").as[List[JsObject]].find(l => (l \ "currency").as[String] == "USD") match {
        // replace real data with random sum changes constantly
          //  case Some(x) => (x \ "amount").as[Int]
          case Some(x) => Random.nextInt(5000)
          case _ => 0
        }
      )}
  }

  def packState(point: PointData, state: String): JsObject = {
    Json.obj(
      "state" -> JsString(state),
      "data" -> Json.obj(
        "id" -> point.id,
        "address" -> point.address,
        "place" -> point.place,
        "dollars" -> point.dollars
      ))
  }
}
