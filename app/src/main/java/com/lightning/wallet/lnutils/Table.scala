package com.lightning.wallet.lnutils

import spray.json._
import com.lightning.wallet.ln.PaymentInfo._
import com.lightning.wallet.lnutils.ImplicitJsonFormats._
import com.lightning.wallet.ln.Tools.{none, random, runAnd}
import com.lightning.wallet.lnutils.olympus.CloudData
import net.sqlcipher.database.SQLiteDatabase
import android.content.Context
import android.net.Uri


object OlympusTable extends Table {
  val (table, identifier, url, data, auth, order, removable) = ("olympus", "identifier", "url", "data", "auth", "ord", "removable")
  val newSql = s"INSERT OR IGNORE INTO $table ($identifier, $url, $data, $auth, $order, $removable) VALUES (?, ?, ?, ?, ?, ?)"
  val updMetaSql = s"UPDATE $table SET $url = ?, $auth = ?, $order = ? WHERE $identifier = ?"
  val updDataSql = s"UPDATE $table SET $data = ? WHERE $identifier = ?"
  val selectAllSql = s"SELECT * FROM $table ORDER BY $order ASC"
  val killSql = s"DELETE FROM $table WHERE $identifier = ?"

  val createSql = s"""
    CREATE TABLE $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $identifier TEXT NOT NULL UNIQUE,
      $url TEXT NOT NULL UNIQUE,
      $data TEXT NOT NULL,
      $auth INTEGER NOT NULL,
      $order INTEGER NOT NULL,
      $removable INTEGER NOT NULL
    )"""
}

object ChannelTable extends Table {
  val Tuple3(table, identifier, data) = Tuple3("channel", "identifier", "data")
  val newSql = s"INSERT OR IGNORE INTO $table ($identifier, $data) VALUES (?, ?)"
  val updSql = s"UPDATE $table SET $data = ? WHERE $identifier = ?"
  val selectAllSql = s"SELECT * FROM $table ORDER BY $id DESC"
  val killSql = s"DELETE FROM $table WHERE $identifier = ?"

  val createSql = s"""
    CREATE TABLE $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $identifier TEXT NOT NULL UNIQUE,
      $data TEXT NOT NULL
    )"""
}

object BadEntityTable extends Table {
  val (table, resId, resType, targetNodeId, expires) = ("badentity", "resid", "restype", "targetnodeid", "expires")
  val selectSql = s"SELECT * FROM $table WHERE $expires > ? AND $targetNodeId IN (?, ?) ORDER BY $id DESC LIMIT 250"
  val newSql = s"INSERT INTO $table ($resId, $resType, $targetNodeId, $expires) VALUES (?, ?, ?, ?)"
  val killSql = s"DELETE FROM $table WHERE ($resId = ? AND $targetNodeId = ?) OR $expires < ?"

  val createSql = s"""
    CREATE TABLE $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $resId TEXT NOT NULL,
      $resType TEXT NOT NULL,
      $targetNodeId TEXT NOT NULL,
      $expires INTEGER NOT NULL
    );
    CREATE INDEX idx1 ON $table ($expires, $targetNodeId);
    CREATE INDEX idx2 ON $table ($resId, $targetNodeId);
    COMMIT"""
}

object PaymentTable extends Table {
  val (search, limit) = ("search", 24)
  val (table, pr, preimage, incoming, status, stamp) = ("payment", "pr", "preimage", "incoming", "status", "stamp")
  val (description, hash, firstMsat, lastMsat, lastExpiry) = ("description", "hash", "firstMsat", "lastMsat", "lastExpiry")
  val insert10 = s"$pr, $preimage, $incoming, $status, $stamp, $description, $hash, $firstMsat, $lastMsat, $lastExpiry"
  val searchInner = s"SELECT DISTINCT $hash FROM $fts$table WHERE $search MATCH ? LIMIT $limit"

  // Inserting, selecting
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $hash) VALUES (?, ?)"
  val newSql = s"INSERT OR IGNORE INTO $table ($insert10) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  val selectRecentSql = s"SELECT * FROM $table WHERE $status <> $HIDDEN ORDER BY $id DESC LIMIT $limit"
  val searchSql = s"SELECT * FROM $table WHERE $hash IN ($searchInner)"
  val selectSql = s"SELECT * FROM $table WHERE $hash = ?"

  // Updating, creating
  val updStatusSql = s"UPDATE $table SET $status = ? WHERE $hash = ?"
  val updLastSql = s"UPDATE $table SET $lastMsat = ?, $lastExpiry = ? WHERE $hash = ?"
  val updFailAllWaitingSql = s"UPDATE $table SET $status = $FAILURE WHERE $status = $WAITING"
  val updOkOutgoingSql = s"UPDATE $table SET $status = $SUCCESS, $preimage = ? WHERE $hash = ?"
  val updOkIncomingSql = s"UPDATE $table SET $status = $SUCCESS, $firstMsat = ?, $stamp = ? WHERE $hash = ?"
  val createVSql = s"CREATE VIRTUAL TABLE $fts$table USING $fts($search, $hash)"

  val createSql = s"""
    CREATE TABLE $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $pr STRING NOT NULL,
      $preimage STRING NOT NULL,
      $incoming INTEGER NOT NULL,
      $status INTEGER NOT NULL,
      $stamp INTEGER NOT NULL,
      $description STRING NOT NULL,
      $hash STRING UNIQUE NOT NULL,
      $firstMsat INTEGER NOT NULL,
      $lastMsat INTEGER NOT NULL,
      $lastExpiry INTEGER NOT NULL
    );
    CREATE INDEX idx1 ON $table ($status);
    CREATE INDEX idx2 ON $table ($hash);
    COMMIT"""
}

trait Table { val (id, fts) = "_id" -> "fts4" }
class CipherOpenHelper(context: Context, name: String, secret: String)
extends net.sqlcipher.database.SQLiteOpenHelper(context, name, null, 1) {

  SQLiteDatabase loadLibs context
  val base = getWritableDatabase(secret)
  def onUpgrade(db: SQLiteDatabase, oldVer: Int, newVer: Int) = none
  def change(sql: String, params: Any*) = base.execSQL(sql, params.map(_.toString).toArray)
  def select(sql: String, params: Any*) = base.rawQuery(sql, params.map(_.toString).toArray)
  def sqlPath(tbl: String) = Uri parse s"sqlite://com.lightning.wallet/table/$tbl"

  def txWrap(process: => Unit) = try {
    runAnd(base.beginTransaction)(process)
    base.setTransactionSuccessful
  } finally base.endTransaction

  def onCreate(dbs: SQLiteDatabase) = {
    dbs execSQL BadEntityTable.createSql
    dbs execSQL PaymentTable.createVSql
    dbs execSQL PaymentTable.createSql
    dbs execSQL ChannelTable.createSql
    dbs execSQL OlympusTable.createSql

    // Randomize an order of two available default servers
    val (ord1, ord2) = if (random.nextBoolean) ("0", "1") else ("1", "0")
    val emptyData = CloudData(info = None, tokens = Vector.empty, acts = Vector.empty).toJson.toString
    val main: Array[AnyRef] = Array("main-dev-server", "http://213.133.99.89:9002", emptyData, "1", ord1, "0")
    val fallback: Array[AnyRef] = Array("fallback-dev-server", "http://213.133.103.56:9002", emptyData, "0", ord2, "1")

    dbs.execSQL(OlympusTable.newSql, main)
    dbs.execSQL(OlympusTable.newSql, fallback)
  }
}