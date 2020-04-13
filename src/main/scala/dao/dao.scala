package jadeutils.comm.dao

import java.lang.Class
import java.sql.Connection
import java.sql.ResultSet
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.sql.SQLException

import scala.util.Try
import scala.util.Failure
import scala.util.Success

import org.apache.commons.lang.StringUtils.isBlank

import jadeutils.database.orm.Table
import jadeutils.database.orm.Column
import jadeutils.database.orm.Record
import java.sql.PreparedStatement
import scala.math.BigDecimal
import java.util.Date
import java.util.logging.Logger
import jadeutils.common.Logging

trait Dao[T <: Record[K], K] {

	def queryModel(sql: String): Seq[T]

	def queryModel(sql: String, showCols: Set[String]): Seq[T]

	def queryModel(sql: String, values: Seq[Any]): Seq[T]

	def queryModel(sql: String, showCols: Set[String], values: Seq[Any]): Seq[T]

	def queryModel(queryStr: String, params: Map[String, AnyRef]): Seq[T]

	def queryModel(queryStr: String, showCols: Set[String], params: Map[String, AnyRef]): Seq[T]

	def query(sql: String): Seq[Map[String, AnyRef]]

	def query(sql: String, showCols: Set[String]): Seq[Map[String, AnyRef]]

	def query(sql: String, values: Seq[Any]): Seq[Map[String, AnyRef]]

	def query(sql: String, showCols: Set[String], values: Seq[Any]): Seq[Map[String, AnyRef]]

	def query(queryStr: String, params: Map[String, AnyRef]): Seq[Map[String, AnyRef]]

	def query(queryStr: String, showCols: Set[String], params: Map[String, Object]): Seq[Map[String, AnyRef]]

	def getById(id: K): Try[T]

	def executeUpdate(sql: String, params: Map[String, AnyRef]): Try[Int]

	def insert(model: T): Try[Unit]

	def update(model: T): Try[Unit]

}

abstract class JDBCTemplateDao[T <: Record[K], K](datasource: DataSourcetHolder) extends Dao[T, K] with Logging {

	val genType: Type = this.getClass().getGenericSuperclass()
	val paramType: ParameterizedType = genType.asInstanceOf[ParameterizedType]
	val params: Array[Type] = paramType.getActualTypeArguments()
	val entryClass: Class[T] = params(0).asInstanceOf[Class[T]]

	def getById(id: K): Try[T] = {
		if (null == id) {
			Failure(new RuntimeException("id cannot be null"))
		} else {
			val columns = ORMUtil.getColumns[T, K](entryClass).mkString(",")
			val table = ORMUtil.getTableName[T, K](entryClass)
			val sql = "select %s from %s where id = ?".format(
					columns, table)
			val recs = queryModel(sql, Seq(id))
			if (recs.size == 1) Success(recs(0)) else if (recs.size < 1) {
				Failure(new RuntimeException("no match rec"))
			} else Failure(new RuntimeException("rec with same primary key"))
		}
	}

	def queryModel(sql: String): Seq[T] = {
		queryModel(sql, Set.empty[String], Map.empty[String, Any])
	}

	def queryModel(sql: String, showCols: Set[String]): Seq[T] = {
		queryModel(sql, showCols, Map.empty[String, Any])
	}

	def queryModel(sql: String, values: Seq[Any]): Seq[T] = {
		queryModel(sql, Set.empty[String], values)
	}

	def queryModel(queryStr: String, params: Map[String, Any]): Seq[T] = {
		queryModel(queryStr, Set.empty[String], params)
	}

	def queryModel( //
		queryStr: String, showCols: Set[String], params: Map[String, Any] //
	): Seq[T] = {
		val values = ORMUtil.parseValues(queryStr, params)
		val sql = ORMUtil.parseQuery(queryStr)
		queryModel(sql, showCols, values)
	}

	def queryModel( //
		sql: String, showCols: Set[String], values: Seq[Any] //
	): Seq[T] = {
		val rs = baseQuery(sql, showCols, values)
		if (!datasource.isInTransaction()) {
			// close jdbc connection if transaction is over
			datasource.retrunBack()
		}
		ORMUtil.allRow2obj[T, K](entryClass, showCols, rs)
	}

	def query(sql: String): Seq[Map[String, AnyRef]] = {
		query(sql, Set.empty[String])
	}

	def query(sql: String, showCols: Set[String]): Seq[Map[String, AnyRef]] = {
		query(sql, showCols, Seq.empty[Any])
	}

	def query(sql: String, values: Seq[Any]): Seq[Map[String, AnyRef]] = {
		query(sql, Set.empty[String], values)
	}

	def query(queryStr: String, params: Map[String, AnyRef]): Seq[Map[String, AnyRef]] = {
		this.query(queryStr, Set.empty[String], params)
	}

	def query( //
	queryStr: String, showCols: Set[String], params: Map[String, AnyRef] //
	): Seq[Map[String, AnyRef]] = {
		val values = ORMUtil.parseValues(queryStr, params)
		val sql = ORMUtil.parseQuery(queryStr)
		query(sql, showCols, values)
	}

	def query( //
		sql: String, showCols: Set[String], values: Seq[Any] //
	): Seq[Map[String, AnyRef]] = {
		val rs = baseQuery(sql, showCols, values)
		if (!datasource.isInTransaction()) {
			// close jdbc connection if transaction is over
			datasource.retrunBack()
		}
		ORMUtil.allRow2map(showCols, rs)
	}

	private[this] def baseQuery( //
		sql: String, showCols: Set[String], values: Seq[Any] //
	): ResultSet = {
		logDebug("sql-query: {} \n     cols: {} \n     vals: {}", //
				sql, showCols, values)
		val conn = datasource.connection()
		val ps = ORMUtil.setQueryValues( //
			conn.get.prepareStatement(sql), values
		)
		ps.executeQuery()
	}

	def executeUpdate(sql: String, params: Map[String, AnyRef]): Try[Int] = {
		val conn = datasource.connection()
		val ps = conn.get.prepareStatement(sql)
		val result = try {
			Success(ps.executeUpdate())
		} catch { case e: Exception => Failure(e) }
		if (!datasource.isInTransaction()) {
			// close jdbc connection if transaction is over
			datasource.retrunBack()
		}
		result
	}

}

object ORMUtil {
	import scala.collection.mutable.{Map => MMap}

	def allRow2map(showCols: Set[String], rs: ResultSet): Seq[Map[String, AnyRef]] = {
		var lst: List[Map[String, AnyRef]] = Nil
		while (rs.next()) { lst = row2map(showCols, rs) :: lst }
		lst.reverse
	}

	def row2map(showCols: Set[String], rs: ResultSet): Map[String, AnyRef] = {
		val map: MMap[String, AnyRef] = MMap.empty
		for (name <- showCols) {
			try {
				map.put(name, rs.getObject(name));
			} catch { case e: SQLException => e.printStackTrace() }
		}
		map.toMap
	}

	def allRow2obj[T <: Record[K], K](clazz: Class[T], showCols: Set[String], rs: ResultSet): Seq[T] = {
		var lst: List[T] = Nil
		while (rs.next()) { lst = row2obj[T, K](clazz, showCols, rs) :: lst }
		lst.reverse
	}

	def row2obj[T <: Record[K], K](clazz: Class[T], showCols: Set[String], rs: ResultSet): T = {
		val obj = clazz.getDeclaredConstructor(clazz).newInstance();
		for (f <- clazz.getDeclaredFields()) {
			val clm: Column = f.getAnnotation(classOf[Column])
			if (null == clm) { /* skip this field */ } else {
				val fldName = f.getName
				val colName = if (isBlank(clm.column())) fldName else clm.column
				if (null != showCols && !showCols.isEmpty && //
						!showCols.contains(colName) && !showCols.contains(f.getName)) //
				{ /* skip this column */ } else {
					try {
						val colValue = rs.getObject(colName)
						f.setAccessible(true);
						f.set(obj, colValue);
					} catch {
						case e: SQLException => e.printStackTrace()
						case e: IllegalArgumentException => e.printStackTrace()
						case e: IllegalAccessException => e.printStackTrace()
					}
				}
			}
		}
		obj
	}
	
	def obj2kv[T <: Record[K], K](clazz: Class[T], obj: T): Seq[(String, Any)] = {
		var lst: List[(String, Any)] = Nil
		for (f <- clazz.getDeclaredFields()) {
			val clm: Column = f.getAnnotation(classOf[Column])
			if (null == clm) { /* skif this field */ } else {
				val fldName = f.getName
				val colName = if (isBlank(clm.column)) fldName else clm.column
				try {
					f.setAccessible(true)
					lst = (colName, f.get(obj)) :: lst
				} catch {
					case e: SQLException => e.printStackTrace()
					case e: IllegalArgumentException => e.printStackTrace()
					case e: IllegalAccessException => e.printStackTrace()
				}
			}
		}
		lst
	}
	
	def getColumns[T <: Record[K], K](clazz: Class[T]): Seq[String] = {
		var columns: List[String] = Nil
		for (f <- clazz.getDeclaredFields()) {
			val clm: Column = f.getAnnotation(classOf[Column])
			if (null == clm) { /* skif this field */ } else {
				val fldName = f.getName
				val colName = if (isBlank(clm.column())) fldName else clm.column
				columns = "`%s`".format(colName) :: columns
			}
		}
		columns
	}

	def getTableName[T <: Record[K], K](clazz: Class[T]): Try[String] = {
		val tbl = clazz.getAnnotation(classOf[Table])
		if (null == tbl) Failure(new RuntimeException("Not Db Entry")) else {
			val database = if (isBlank(tbl.database)) "" else tbl.database
			val table = if (isBlank(tbl.table)) clazz.getName else tbl.table
			if (isBlank(database)) Success("`%s`".format(table)) else {
				Success("`%s`.`%s`".format(database, table))
			}
		}
	}

	val paramRegex = """:([-_0-9a-zA-Z]+)""".r

	def parseValues(query: String, params: Map[String, Any]): Seq[Any] = {
		for (m <- paramRegex findAllMatchIn query) yield m.group(1)
	}.toSeq

	/* 替换查询语句中以冒号开头的参数名替换为sql标准中的问号参数 */
	def parseQuery(query: String): String = paramRegex.replaceAllIn(query, "?")

	/* 设置sql中问号参数的值 */
	def setQueryValues(ps: PreparedStatement, values: Seq[Any]): PreparedStatement = {
		for (n <- 1 to values.size) {
			values(n) match {
				case o: scala.Byte => ps.setByte(n, o)
				case o: scala.Short => ps.setShort(n, o)
				case o: scala.Int => ps.setInt(n, o)
				case o: scala.Long => ps.setLong(n, o)
				case o: scala.Float => ps.setFloat(n, o)
				case o: scala.Double => ps.setDouble(n, o)
				case o: scala.BigDecimal => ps.setBigDecimal(n, o.bigDecimal)
				case o: java.lang.Byte => ps.setByte(n, o)
				case o: java.lang.Short => ps.setShort(n, o)
				case o: java.lang.Integer => ps.setInt(n, o)
				case o: java.lang.Long => ps.setLong(n, o)
				case o: java.lang.Float => ps.setFloat(n, o)
				case o: java.lang.Double => ps.setDouble(n, o)
				case o: java.math.BigDecimal => ps.setBigDecimal(n, o)
				// 
				case o: String => ps.setString(n, o)
				case o: Date => ps.setDate(n, new java.sql.Date(o.getTime))
				//
				case _ => ps.setObject(n, null)
			}
		}
		ps
	}

}
