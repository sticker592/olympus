package com.lightning.olympus

import org.http4s.dsl._
import com.lightning.wallet.ln._
import com.lightning.olympus.Utils._
import scala.collection.JavaConverters._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import org.http4s.server.{Server, ServerApp}
import org.http4s.{HttpService, Response}

import com.lightning.olympus.Router.ShortChannelIdSet
import com.lightning.olympus.database.MongoDatabase
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import fr.acinq.bitcoin.Crypto.PublicKey
import org.json4s.jackson.Serialization
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import java.math.BigInteger


object LNCloud extends ServerApp {
  type ProgramArguments = List[String]
  def server(args: ProgramArguments): Task[Server] = {
    values = Vals(new BigInteger("33337641954423495759821968886025053266790003625264088739786982511471995762588"),
      MilliSatoshi(7000000), 50, btcApi = "http://foo:bar@127.0.0.1:18332", zmqApi = "tcp://127.0.0.1:29000",
      eclairApi = "http://127.0.0.1:8080", eclairIp = "127.0.0.1", eclairPort = 9735, rewindRange = 144 * 7,
      eclairNodeId = "0299439d988cbf31388d59e3d6f9e184e7a0739b8b8fcdc298957216833935f9d3",
      checkByToken = true)

    LNParams.setup(random getBytes 32)
    val httpLNCloudServer = new Responder
    val postLift = UrlFormLifter(httpLNCloudServer.http)
    BlazeBuilder.bindHttp(9001).mountService(postLift).start
  }
}

class Responder { me =>
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]
  private val exchangeRates = new ExchangeRates
  private val blindTokens = new BlindTokens
  private val feeRates = new FeeRates
  private val db = new MongoDatabase
  private val V1 = Root / "v1"
  private val BODY = "body"

  // Watching chain and socket
  new ListenerManager(db).connect

  private val check =
    values.checkByToken match {
      case true => new BlindTokenChecker
      case false => new SignatureChecker
    }

  val http = HttpService {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> V1 / "blindtokens" / "info" => new ECKey(random) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      Ok apply ok(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.quantity)
    }

    // Record tokens and send an Invoice
    case req @ POST -> V1 / "blindtokens" / "buy" =>
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")
      val prunedTokens = toClass[StringSeq](hex2Ascii apply tokens) take values.quantity

      val requestInvoice = for {
        pkItem <- blindTokens.cache get sesKey
        request = blindTokens generateInvoice values.price
        blind = BlindData(request.paymentHash, pkItem.data, prunedTokens)
      } yield {
        db.putPendingTokens(blind, sesKey)
        ok(PaymentRequest write request)
      }

      requestInvoice match {
        case Some(invoice) => Ok apply invoice
        case None => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> V1 / "blindtokens" / "redeem" =>
      // We only sign tokens if the request has been paid

      val blindSignatures = for {
        blindData <- db.getPendingTokens(req params "seskey")
        if db isPaymentFulfilled blindData.paymentHash

        bigInts = for (blindToken <- blindData.tokens) yield new BigInteger(blindToken)
      } yield bigInts.map(bigInt => blindTokens.signer.blindSign(bigInt, blindData.k).toString)

      blindSignatures match {
        case Some(sigs) => Ok apply ok(sigs:_*)
        case None => Ok apply error("notfound")
      }

    // ROUTER

    case req @ POST -> V1 / "router" / "routes"
      if Router.black.contains(req params "from") =>
      Ok apply error("fromblacklisted")

    case req @ POST -> V1 / "router" / "routes" =>
      val Seq(noNodes, noChannels, from, to) = extract(req.params, identity, "nodes", "channels", "from", "to")
      val withoutNodeIds = toClass[StringSeq](hex2Ascii apply noNodes).toSet take 100 map string2PublicKey
      val withoutShortChannelIds = toClass[ShortChannelIdSet](hex2Ascii apply noChannels) take 100
      val paths = Router.finder.safeFindPaths(withoutNodeIds, withoutShortChannelIds, from, to)
      val encoded = for (hops <- paths) yield hops.map(hopCodec.encode(_).require.toHex)
      Ok apply ok(encoded:_*)

    case req @ POST -> V1 / "router" / "nodes" =>
      val query = req.params("query").trim.take(32).toLowerCase
      // A node may be well connected but not public and thus having no node announcement
      val announces = if (query.nonEmpty) Router.maps.searchTrie.getValuesForKeysStartingWith(query).asScala
        else Router.maps.nodeId2Chans.seq take 48 flatMap { case key \ _ => Router.maps.nodeId2Announce get key }

      // Json4s serializes tuples as maps while we need lists so we explicitly fix that here
      val encoded = announces.take(24).map(announce => nodeAnnouncementCodec.encode(announce).require.toHex)
      val sizes = announces.take(24).map(announce => Router.maps.nodeId2Chans.mapping(announce.nodeId).size)
      val fixed = encoded zip sizes map { case enc \ size => enc :: size :: Nil }
      Ok apply ok(fixed.toList:_*)

    // TRANSACTIONS

    case req @ POST -> V1 / "txs" / "get" =>
      val rawTxids = hex2Ascii(req params "txids")
      val txids = toClass[StringSeq](rawTxids) take 10
      Ok apply ok(db getTxs txids:_*)

    // ARBITRARY DATA

    case req @ POST -> V1 / "data" / "put" => check.verify(req.params) {
      val Seq(key, userData) = extract(req.params, identity, "key", BODY)
      db.putData(key, userData)
      Ok apply ok("done")
    }

    case req @ POST -> V1 / "data" / "get" =>
      val results = db.getDatas(req params "key")
      Ok apply ok(results:_*)

    // FEERATE AND EXCHANGE RATES

    case POST -> Root / _ / "rates" / "get" =>
      val feeEstimates = feeRates.rates.mapValues(_ getOrElse 0)
      val exchanges = exchangeRates.currencies.map(c => c.code -> c.average)
      val processedExchanges = exchanges.toMap.mapValues(_ getOrElse 0)
      val result = feeEstimates :: processedExchanges :: Nil
      Ok apply ok(result)

    case GET -> Root / _ / "rates" / "state" =>
      Ok(exchangeRates.displayState mkString "\r\n\r\n")

    // NEW VERSION WARNING AND TESTS

    case req @ POST -> Root / _ / "check" => check.verify(req.params) {
      // This is a test where we simply check if a user supplied data is ok
      Ok apply ok(req.params apply BODY)
    }

    case POST -> Root / "v2" / _ =>
      Ok apply error("mustupdate")
  }

  // HTTP answer as JSON array
  def ok(data: Any*): String = Serialization write "ok" +: data
  def error(data: Any*): String = Serialization write "error" +: data

  // Ckecking if incoming data can be accepted either by signature or disposable blind token
  trait DataChecker { def verify(params: HttpParams)(next: => TaskResponse): TaskResponse }

  class BlindTokenChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(point, cleartoken, clearsig) = extract(params, identity, "point", "cleartoken", "clearsig")
      lazy val signatureIsFine = blindTokens.signer.verifyClearSig(clearMsg = new BigInteger(cleartoken),
        clearSignature = new BigInteger(clearsig), point = blindTokens decodeECPoint point)

      if (params(BODY).length > 250000) Ok apply error("bodytoolarge")
      else if (db isClearTokenUsed cleartoken) Ok apply error("tokenused")
      else if (!signatureIsFine) Ok apply error("tokeninvalid")
      else try next finally db putClearToken cleartoken
    }
  }

  class SignatureChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(data, sig, key) = extract(params, BinaryData.apply, BODY, "sig", "key")
      lazy val sigOk = Crypto.verifySignature(Crypto sha256 data, sig, PublicKey apply key)

      val userPubKeyIsPresent = db keyExists key.toString
      if (params(BODY).length > 250000) Ok apply error("bodytoolarge")
      else if (!userPubKeyIsPresent) Ok apply error("keynotfound")
      else if (!sigOk) Ok apply error("siginvalid")
      else next
    }
  }
}