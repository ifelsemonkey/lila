package lila.security

import org.joda.time.DateTime
import play.api.mvc.RequestHeader
import reactivemongo.api.ReadPreference
import reactivemongo.bson.Macros

import lila.common.{ HTTPRequest, ApiVersion, IpAddress }
import lila.db.BSON.BSONJodaDateTimeHandler
import lila.db.dsl._

object Store {

  // dirty
  private val coll = Env.current.storeColl

  private implicit val fingerHashBSONHandler = stringIsoHandler[FingerHash]

  private[security] def save(
    sessionId: String,
    userId: String,
    req: RequestHeader,
    apiVersion: Option[ApiVersion]
  ): Funit =
    coll.insert($doc(
      "_id" -> sessionId,
      "user" -> userId,
      "ip" -> HTTPRequest.lastRemoteAddress(req),
      "ua" -> HTTPRequest.userAgent(req).|("?"),
      "date" -> DateTime.now,
      "up" -> true,
      "api" -> apiVersion.map(_.value)
    )).void

  private val userIdProjection = $doc("user" -> true, "_id" -> false)
  private val userIdFingerprintProjection = $doc(
    "user" -> true,
    "fp" -> true,
    "date" -> true,
    "_id" -> false
  )

  def userId(sessionId: String): Fu[Option[String]] =
    coll.find(
      $doc("_id" -> sessionId, "up" -> true),
      userIdProjection
    ).uno[Bdoc] map { _ flatMap (_.getAs[String]("user")) }

  case class UserIdAndFingerprint(user: String, fp: Option[String], date: DateTime) {
    def isOld = date isBefore DateTime.now.minusDays(1)
  }
  private implicit val UserIdAndFingerprintBSONReader = Macros.reader[UserIdAndFingerprint]

  def userIdAndFingerprint(sessionId: String): Fu[Option[UserIdAndFingerprint]] =
    coll.find(
      $doc("_id" -> sessionId, "up" -> true),
      userIdFingerprintProjection
    ).uno[UserIdAndFingerprint]

  def setDateToNow(sessionId: String): Unit =
    coll.updateFieldUnchecked($id(sessionId), "date", DateTime.now)

  def delete(sessionId: String): Funit =
    coll.update(
      $id(sessionId),
      $set("up" -> false)
    ).void

  def closeUserAndSessionId(userId: String, sessionId: String): Funit =
    coll.update(
      $doc("user" -> userId, "_id" -> sessionId, "up" -> true),
      $set("up" -> false)
    ).void

  def closeUserExceptSessionId(userId: String, sessionId: String): Funit =
    coll.update(
      $doc("user" -> userId, "_id" -> $ne(sessionId), "up" -> true),
      $set("up" -> false),
      multi = true
    ).void

  // useful when closing an account,
  // we want to logout too
  def disconnect(userId: String): Funit = coll.update(
    $doc("user" -> userId),
    $set("up" -> false),
    multi = true
  ).void

  private implicit val UserSessionBSONHandler = Macros.handler[UserSession]
  def openSessions(userId: String, nb: Int): Fu[List[UserSession]] =
    coll.find(
      $doc("user" -> userId, "up" -> true)
    ).sort($doc("date" -> -1)).cursor[UserSession]().gather[List](nb)

  def setFingerPrint(id: String, fp: FingerPrint): Fu[FingerHash] =
    FingerHash(fp) match {
      case None => fufail(s"Can't hash $id's fingerprint $fp")
      case Some(hash) => coll.updateField($doc("_id" -> id), "fp", hash) inject hash
    }

  case class Info(ip: IpAddress, ua: String, fp: Option[String]) {
    def fingerprint = fp.map(_.toString)
  }
  private implicit val InfoBSONHandler = Macros.handler[Info]

  def findInfoByUser(userId: String): Fu[List[Info]] =
    coll.find(
      $doc("user" -> userId),
      $doc("_id" -> false, "ip" -> true, "ua" -> true, "fp" -> true)
    ).list[Info]()

  private case class DedupInfo(_id: String, ip: String, ua: String) {
    def compositeKey = s"$ip $ua"
  }
  private implicit val DedupInfoBSONHandler = Macros.handler[DedupInfo]

  def dedup(userId: String, keepSessionId: String): Funit =
    coll.find($doc(
      "user" -> userId,
      "up" -> true
    )).sort($doc("date" -> -1))
      .cursor[DedupInfo]().gather[List]() flatMap { sessions =>
        val olds = sessions.groupBy(_.compositeKey).values.map(_ drop 1).flatten
          .filter(_._id != keepSessionId)
        coll.remove($inIds(olds.map(_._id))).void
      }

  private[security] def recentByIpExists(ip: IpAddress): Fu[Boolean] =
    coll.exists(
      $doc("ip" -> ip, "date" -> $gt(DateTime.now minusDays 7)),
      readPreference = ReadPreference.secondaryPreferred
    )

  private[security] def recentByPrintExists(fp: FingerPrint): Fu[Boolean] =
    FingerHash(fp) ?? { hash =>
      coll.exists(
        $doc("fp" -> hash, "date" -> $gt(DateTime.now minusDays 7)),
        readPreference = ReadPreference.secondaryPreferred
      )
    }
}
