package com.lightning.olympus

import com.lightning.walletapp.ln._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import com.lightning.walletapp.ln.wire.LightningMessageCodecs._
import com.lightning.walletapp.ln.Tools.random

import spray.json._
import org.http4s.dsl._
import fr.acinq.bitcoin._
import com.lightning.olympus.Utils._
import scala.collection.JavaConverters._
import com.lightning.olympus.database.MongoDatabase
import org.http4s.server.middleware.UrlFormLifter
import com.lightning.olympus.zmq.ZMQSupervisor
import com.lightning.olympus.JsonHttpUtils.to
import org.http4s.server.SSLSupport.StoreInfo
import scala.concurrent.duration.DurationInt
import org.http4s.server.blaze.BlazeBuilder
import language.implicitConversions
import org.http4s.server.ServerApp
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import java.math.BigInteger
import java.nio.file.Paths

import java.net.{InetAddress, InetSocketAddress}
import org.http4s.{HttpService, Response}
import rx.lang.scala.{Observable => Obs}
import akka.actor.{ActorSystem, Props}


object Olympus extends ServerApp {
  type ProgramArguments = List[String]
  def server(args: ProgramArguments) = {

    args match {
      case List("testrun") =>
        val description = "Storage tokens for backup Olympus server at 10.0.2.2"
        val eclairProvider = EclairProvider(500000L, 50, description, "http://127.0.0.1:8080", "pass")
        values = Vals(privKey = "33337641954423495759821968886025053266790003625264088739786982511471995762588",
          btcApi = "http://foo:bar@127.0.0.1:18332", zmqApi = "tcp://127.0.0.1:29000", eclairSockIp = "127.0.0.1",
          eclairSockPort = 9735, eclairNodeId = "0218bc75cba78d378d864a0f41d4ccd67eb1eaa829464d37706702003069c003f8",
          rewindRange = 7, ip = "127.0.0.1", port = 9003, eclairProvider, minCapacity = 100000L,
          sslFile = "/home/anton/Desktop/olympus/keystore.jks", sslPass = "pass123")

      case List("production", rawVals) =>
        values = to[Vals](rawVals)
    }

    LNParams.setup(random getBytes 32)
    val httpLNCloudServer = new Responder
    val postLift = UrlFormLifter(httpLNCloudServer.http)
    val sslInfo = StoreInfo(Paths.get(values.sslFile).toAbsolutePath.toString, values.sslPass)
    BlazeBuilder/*.withSSL(sslInfo, values.sslPass)*/.bindHttp(values.port, values.ip).mountService(postLift).start
  }
}

class Responder { me =>
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  implicit def js2Task(js: JsValue): TaskResponse = Ok(js.toString)
  private val (bODY, oK, eRROR) = Tuple3("body", "ok", "error")
  private val exchangeRates = new ExchangeRates
  private val blindTokens = new BlindTokens
  private val feeRates = new FeeRates
  private val db = new MongoDatabase

  val system = ActorSystem("zmq-system")
  // Start watching Bitcoin blocks and transactions via ZMQ interface
  val supervisor = system actorOf Props.create(classOf[ZMQSupervisor], db)
  LNConnector.connect

  val http = HttpService {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> Root / "blindtokens" / "info" => new ECKey(random) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      val res = (blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.paymentProvider.quantity)
      Tuple2(oK, res).toJson
    }

    // Record tokens and send an Invoice
    case req @ POST -> Root / "blindtokens" / "buy" =>
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")
      val pruned = hex2Ascii andThen to[StringVec] apply tokens take values.paymentProvider.quantity

      blindTokens.cache get sesKey map { item =>
        val Charge(hash, id, serializedPR, false) = values.paymentProvider.generateInvoice
        db.putPendingTokens(data = BlindData(hash, id, item.data, pruned), sesKey)
        serializedPR
      } match {
        case Some(invoice) => Tuple2(oK, invoice).toJson
        case None => Tuple2(eRROR, "notfound").toJson
      }

    // Provide signed blind tokens
    case req @ POST -> Root / "blindtokens" / "redeem" =>
      // We only sign tokens if the request has been fulfilled
      val tokens = db.getPendingTokens(req params "seskey")
      val isOk = tokens map values.paymentProvider.isPaid

      isOk -> tokens match {
        case Some(true) \ Some(data) => Tuple2(oK, blindTokens sign data).toJson
        case Some(false) \ _ => Tuple2(eRROR, "notfulfilled").toJson
        case _ => Tuple2(eRROR, "notfound").toJson
      }

    // ROUTER

    case req @ POST -> Root / "router" / "routes" =>
      val InRoutes(badNodes, badChans, from, dest) = req.params andThen hex2Ascii andThen to[InRoutes] apply "params"
      val paths = Router.finder.findPaths(badNodes take 160, badChans take 160, from take 4, dest, sat = 0L)
      Tuple2(oK, paths).toJson

    case req @ POST -> Root / "router" / "routesplus" =>
      val InRoutesPlus(sat, badNodes, badChans, from, dest) = req.params andThen hex2Ascii andThen to[InRoutesPlus] apply "params"
      val paths = Router.finder.findPaths(badNodes take 160, badChans take 160, from take 4, dest, sat = (sat * 1.2).toLong)
      Tuple2(oK, paths).toJson

    case req @ POST -> Root / "router" / "nodes" =>
      val query = req.params("query").trim.take(32).toLowerCase
      // A node may be well connected but not public and thus having no node announcement
      val announces = if (query.nonEmpty) Router.searchTrie.getValuesForKeysStartingWith(query).asScala
        else Router.nodeId2Chans.scoredNodeSuggestions take 48 flatMap Router.nodeId2Announce.get

      val encoded = announces.take(24).map(ann => nodeAnnouncementCodec.encode(ann).require.toHex)
      val sizes = announces.take(24).map(ann => Router.nodeId2Chans.dict(ann.nodeId).size)
      Tuple2(oK, encoded zip sizes).toJson

    // TRANSACTIONS AND BLOCKS

    case req @ POST -> Root / "block" / "get" =>
      val block = bitcoin.getBlock(req params "hash")
      val data = block.height -> block.tx.asScala.toVector
      Tuple2(oK, data).toJson

    case req @ POST -> Root / "txs" / "get" =>
      // Given a list of commit tx ids, fetch all child txs which spend their outputs
      val txIds = req.params andThen hex2Ascii andThen to[StringVec] apply "txids" take 24
      Tuple2(oK, db getTxs txIds).toJson

    case req @ POST -> Root / "txs" / "schedule" => verify(req.params) {
      val txs = req.params andThen hex2Ascii andThen to[StringVec] apply bODY
      for (raw <- txs take 16) db.putScheduled(Transaction read raw)
      Tuple2(oK, "done").toJson
    }

    // ARBITRARY DATA

    case req @ POST -> Root / "data" / "put" => verify(req.params) {
      val Seq(key, userDataHex) = extract(req.params, identity, "key", bODY)
      db.putData(key, userDataHex)
      Tuple2(oK, "done").toJson
    }

    case req @ POST -> Root / "data" / "get" =>
      val results = db.getData(req params "key")
      Tuple2(oK, results).toJson

    // FEERATE AND EXCHANGE RATES

    case POST -> Root / "rates" / "get" =>
      val feesPerBlock = for (k \ v <- feeRates.rates) yield (k.toString, v getOrElse 0D)
      val fiatRates = for (cur <- exchangeRates.currencies) yield (cur.code, cur.average)
      val response = Tuple2(feesPerBlock.toMap, fiatRates.toMap)
      Tuple2(oK, response).toJson

    case GET -> Root / "rates" / "state" =>
      val fiat = exchangeRates.displayState mkString "\r\n\r\n"
      val bitcoin = feeRates.rates.toString
      Ok(s"$bitcoin\r\n\r\n$fiat")
  }

  def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
    val Seq(point, cleartoken, clearsig) = extract(params, identity, "point", "cleartoken", "clearsig")
    lazy val signatureIsFine = blindTokens.signer.verifyClearSig(clearMsg = new BigInteger(cleartoken),
      clearSignature = new BigInteger(clearsig), point = blindTokens decodeECPoint point)

    if (params(bODY).length > 250000) Tuple2(eRROR, "bodytoolarge").toJson
    else if (db isClearTokenUsed cleartoken) Tuple2(eRROR, "tokenused").toJson
    else if (!signatureIsFine) Tuple2(eRROR, "tokeninvalid").toJson
    else try next finally db putClearToken cleartoken
  }
}

object LNConnector {
  def connect = ConnectionManager connectTo announce
  val inetSockAddress = new InetSocketAddress(InetAddress getByName values.eclairSockIp, values.eclairSockPort)
  val announce = NodeAnnouncement(null, null, 0, values.eclairNodePubKey, null, "Routing source", NodeAddress(inetSockAddress) :: Nil)

  ConnectionManager.listeners += new ConnectionListener {
    override def onMessage(ann: NodeAnnouncement, msg: LightningMessage) = Router receive msg
    override def onOperational(ann: NodeAnnouncement, their: Init) = Tools log "Eclair socket is operational"
    override def onTerminalError(ann: NodeAnnouncement) = ConnectionManager.connections.get(ann).foreach(_.socket.close)
    override def onIncompatible(ann: NodeAnnouncement) = onTerminalError(ann)

    override def onDisconnect(ann: NodeAnnouncement) =
      Obs.just(Tools log "Restarting socket").delay(5.seconds)
        .subscribe(in5Seconds => connect, _.printStackTrace)
  }
}