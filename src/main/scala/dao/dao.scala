package net.jadedungeon.scalautil.dao

import java.lang.Class
import java.sql.Connection
import java.sql.ResultSet
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.sql.SQLException

import scala.util.Try
import scala.util.Failure
import scala.util.Success

import org.apache.commons.lang.StringUtils.{isBlank => isBlankStr}

import java.sql.PreparedStatement
import scala.math.BigDecimal
import java.util.Date
import java.util.logging.Logger
import net.jadedungeon.scalautil.common.Logging

import scala.annotation.StaticAnnotation
import scala.reflect.runtime.universe._
import java.lang.reflect.Field

abstract class Record[K](_id: K, _createTime: Date, _lastChangeTime: Date) {
	
	@Column(notNull = true)              var id: K          = _id
	@Column(column = "create_time")      var createTime     = _createTime
	@Column(column = "last_change_time") var lastChangeTime = _lastChangeTime

	override def toString: String = s"column($id, $createTime, $lastChangeTime)"
}

trait Dao[T <: Record[K], K] {

	def queryModel(sql: String): Try[Seq[T]]

	def queryModel(sql: String, showCols: Set[String]): Try[Seq[T]]

	def queryModel(sql: String, values: Seq[Any]): Try[Seq[T]]

	def queryModel(queryStr: String, params: Map[String, Any]): Try[Seq[T]]

	def queryModel(queryStr: String, showCols: Set[String], params: Map[String, Any]): Try[Seq[T]]

	def queryModel(sql: String, showCols: Set[String], values: Seq[Any]): Try[Seq[T]]

	def query(sql: String): Try[Seq[Map[String, AnyRef]]]

	def query(sql: String, showCols: Set[String]): Try[Seq[Map[String, AnyRef]]]

	def query(sql: String, values: Seq[Any]): Try[Seq[Map[String, AnyRef]]]

	def query(queryStr: String, params: Map[String, Any]): Try[Seq[Map[String, AnyRef]]]

	def query(queryStr: String, showCols: Set[String], params: Map[String, Any]): Try[Seq[Map[String, AnyRef]]]

	def query(sql: String, showCols: Set[String], values: Seq[Any]): Try[Seq[Map[String, AnyRef]]]

	def getById(id: K): Try[Option[T]]

	def getById(id: K, showCols: Set[String]): Try[Option[T]]

	def executeUpdate(queryStr: String, params: Map[String, Any]): Try[Int]

	def executeUpdate(sql: String, values: Seq[Any]): Try[Int]

	def insert(model: T): Try[Int]

	def update(model: T): Try[Int]

	def insertOrUpdate(model: T): Try[Int]

}

abstract class JDBCTemplateDao[T <: Record[K], K](datasource: DataSourcetHolder) extends Dao[T, K] with Logging {

	val genType: Type = this.getClass().getGenericSuperclass()
	val paramType: ParameterizedType = genType.asInstanceOf[ParameterizedType]
	val params: Array[Type] = paramType.getActualTypeArguments()
	val entryClass: Class[T] = params(0).asInstanceOf[Class[T]]

	def getById(id: K): Try[Option[T]] = getById(id, Set.empty[String])

	def getById(id: K, showCols: Set[String]): Try[Option[T]] = if (null == id) {
		Failure(new RuntimeException("id cannot be null"))
	} else try {
		val table = ORMUtil.getTableName[T, K](entryClass, datasource.dialect).get
		val columns = ORMUtil.getColumns[T, K](entryClass, showCols)
		val colStr = { for (s <- columns) yield "`%s`".format(s) }.mkString(",")
		val sql = s"select $colStr from $table where id = ?"
		queryModel(sql, Seq(id)) match {
			case Failure(f) => Failure(f)
			case Success(recs) => {
				if (recs.size == 1) Success(Some(recs(0))) else if (recs.size < 1) {
					Success(None)
				} else Failure(new RuntimeException("rec with same primary key"))
			}
		}
	} catch {
		case e: Exception => Failure(e)
	}

	def queryModel(sql: String): Try[Seq[T]] = {
		queryModel(sql, Set.empty[String], Map.empty[String, Any])
	}

	def queryModel(sql: String, showCols: Set[String]): Try[Seq[T]] = {
		queryModel(sql, showCols, Map.empty[String, Any])
	}

	def queryModel(sql: String, values: Seq[Any]): Try[Seq[T]] = {
		queryModel(sql, Set.empty[String], values)
	}

	def queryModel(queryStr: String, params: Map[String, Any]): Try[Seq[T]] = {
		queryModel(queryStr, Set.empty[String], params)
	}

	def queryModel( //
		queryStr: String, showCols: Set[String], params: Map[String, Any] //
	): Try[Seq[T]] = {
		val values = ORMUtil.parseValues(queryStr, params)
		val sql = ORMUtil.parseQuery(queryStr)
		queryModel(sql, showCols, values)
	}

	def queryModel(sql: String, showCols: Set[String], values: Seq[Any]): Try[Seq[T]] = {
		baseQuery(sql, showCols, values) match {
			case Failure(f) => Failure(f)
			case Success(rs) => {
				val result = ORMUtil.allRow2obj[T, K](entryClass, showCols, rs)
				if (!datasource.isInTransaction()) {
					// close jdbc connection if transaction is over
					datasource.retrunBack()
				}
				Success(result)
			}
		}
	}

	def query(sql: String): Try[Seq[Map[String, AnyRef]]] = {
		query(sql, Set.empty[String])
	}

	def query(sql: String, showCols: Set[String]): Try[Seq[Map[String, AnyRef]]] = {
		query(sql, showCols, Seq.empty[Any])
	}

	def query(sql: String, values: Seq[Any]): Try[Seq[Map[String, AnyRef]]] = {
		query(sql, Set.empty[String], values)
	}

	def query(queryStr: String, params: Map[String, Any]): Try[Seq[Map[String, AnyRef]]] = {
		this.query(queryStr, Set.empty[String], params)
	}

	def query( //
		queryStr: String, showCols: Set[String], params: Map[String, Any] //
	): Try[Seq[Map[String, AnyRef]]] = {
		val values = ORMUtil.parseValues(queryStr, params)
		val sql = ORMUtil.parseQuery(queryStr)
		query(sql, showCols, values)
	}

	def query( //
	sql: String, showCols: Set[String], values: Seq[Any] //
	): Try[Seq[Map[String, AnyRef]]] = {
		baseQuery(sql, showCols, values) match {
			case Failure(f) => Failure(f)
			case Success(rs) => {
				val result = ORMUtil.allRow2map(showCols, rs)
						if (!datasource.isInTransaction()) {
							// close jdbc connection if transaction is over
							datasource.retrunBack()
						}
						Success(result)
			}
		}
	}

	private[this] def baseQuery( //
		sql: String, showCols: Set[String], values: Seq[Any] //
	): Try[ResultSet] = try {
		logDebug("\n sql-query: {} \n      cols: {} \n      vals: {}", //
				sql, showCols, values)
		val conn = datasource.connection()
		val ps = ORMUtil.setQueryValues( //
			conn.get.prepareStatement(sql), values
		)
		val rs = ps.executeQuery()
		Success(rs)
	} catch {
		case e: Exception => Failure(e)
	}

	def insertOrUpdate(model: T): Try[Int] = {
		val table = ORMUtil.getTableName[T, K](entryClass, datasource.dialect).get
		val sql = s"select count(1) as c from $table where id = ?"
		query(sql, Seq(model.id)) match {
			case Failure(f) => Failure(f)
			case Success(recs) => {
				val count = if (recs.length > 0 && recs(0).contains("c")) {
					recs(0).getOrElse("c", 0) match {
						case n: Int => n
						case n: Long => n
						case _ => 0
					}
				} else 0
				logDebug("insertOrUpdate old rec: {}, count: {}", recs, count)
				if (count > 0) update(model) else insert(model)
			}
		}
	}
	
	def insert(model: T): Try[Int] = {
		if (null == model) {
			Failure(new RuntimeException("Rec caonnot by null "))
		} else if(null == model.id) {
			Failure(new RuntimeException("PrimeKey id caonnot by null "))
		} else {
			val table = ORMUtil.getTableName[T, K](entryClass, datasource.dialect).get
			val seq = ORMUtil.obj2kv[T, K](entryClass, model, Set.empty[String])
			val xx = seq.unzip
			val colStr = { for (s <- xx._1) yield "`%s`".format(s) }.mkString(",")
			val values = xx._2
			val markStr = { for (s <- xx._1) yield "?" }.mkString(",")
			val sql = s"INSERT INTO $table ($colStr) VALUES ($markStr)"
			executeUpdate(sql, values)
		}
	}

	def update(model: T): Try[Int] = {
		val table = ORMUtil.getTableName[T, K](entryClass, datasource.dialect).get
		val seq = ORMUtil.obj2kv[T,K](entryClass, model, Set.empty[String])
		val xx = seq.unzip
		val colStr = { for (s <- xx._1) yield "`%s`=?".format(s) }.mkString(",")
		val values: Seq[Any] = xx._2.:+(model.id)
		val sql = s"UPDATE $table SET $colStr WHERE id = ?"
		executeUpdate(sql, values)
	}

	def executeUpdate(queryStr: String, params: Map[String, Any]): Try[Int] = {
		val values: Seq[Any] = ORMUtil.parseValues(queryStr, params)
		val sql: String = ORMUtil.parseQuery(queryStr)
		executeUpdate(sql, values)
	}
	
	def executeUpdate(sql: String, values: Seq[Any]): Try[Int] = {
		logDebug("\nsql-update: {} \n     vals: {}", //
				sql, values)
		val conn = datasource.connection()
		val ps = conn.get.prepareStatement(sql)
		val result = try {
			ORMUtil.setQueryValues(ps, values)
			Success(ps.executeUpdate())
		} catch { case e: Exception => Failure(e) }
		if (!datasource.isInTransaction()) {
			// close jdbc connection if transaction is over
			datasource.retrunBack()
		}
		ps.close()
		result
	}

}

object ORMUtil {
	import scala.collection.mutable.{Map => MMap}

	def allRow2map(showCols: Set[String], rs: ResultSet): Seq[Map[String, AnyRef]] = {
		var lst: List[Map[String, AnyRef]] = Nil
		while (rs.next()) { lst = row2map(showCols, rs) :: lst }
		rs.close()
		lst.reverse
	}

	def row2map(showCols: Set[String], rs: ResultSet): Map[String, AnyRef] = {
		val map: MMap[String, AnyRef] = MMap.empty
		val colCount = rs.getMetaData.getColumnCount
		val colNames = for (c <- 1 to colCount) yield rs.getMetaData.getColumnName(c)
		for (name <- colNames) {
			if (null != showCols && showCols.size > 0 && showCols.contains(name)) {
				/* skip this col */
			} else try {
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
		val obj = clazz.getDeclaredConstructor(Seq.empty[Class[_]]: _*).newInstance();
		for (fld <- getColumnFields(clazz, showCols)) {
			val clm: Column = fld.getAnnotation(classOf[Column])
			val fldName = fld.getName
			val colName = if (isBlankStr(clm.column())) fldName else clm.column
			try {
				val colValue = rs.getObject(colName)
				ORMUtil.setValueInField(fld, obj, colValue)
			} catch {
				case e: SQLException => e.printStackTrace()
				case e: IllegalArgumentException => e.printStackTrace()
				case e: IllegalAccessException => e.printStackTrace()
			}
		}
		obj
	}
	
	def obj2kv[T <: Record[K], K](clazz: Class[T], obj: T, showCols: Set[String]): Seq[(String, Any)] = {
		for (f <- getColumnFields(clazz, showCols)) yield {
			val clm: Column = f.getAnnotation(classOf[Column])
			val colName: String = if (isBlankStr(clm.column())) f.getName else clm.column
			f.setAccessible(true)
			(colName, f.get(obj))
		}
	}
	
	/* 字段对应表的列名 */
	def getColumns[T <: Record[K], K](clazz: Class[_], showCols: Set[String]): Seq[String] = {
		for (f <- getColumnFields(clazz, showCols)) yield {
			val clm: Column = f.getAnnotation(classOf[Column])
			val colName: String = if (isBlankStr(clm.column())) f.getName else clm.column
			"%s".format(colName)
		}
	}
	
	/* 类中有column标记的字段 */
	def getColumnFields[T <: Record[K], K](clazz: Class[_], showCols: Set[String])
		: Seq[Field] = //
	{
		def loop(clazz: Class[_], oldFlds: Seq[Field], showCols: Set[String]): Seq[Field] = {
			var newFlds: List[Field] = Nil
			for (fld <- clazz.getDeclaredFields()) {
				val clm: Column = fld.getAnnotation(classOf[Column])
				if (null == clm) { /* skip this field */ 
				} else {
					val fldName = fld.getName
					val colName = if (isBlankStr(clm.column())) fldName else clm.column
					if (null != showCols && !showCols.isEmpty && //
							!showCols.contains(colName) && //
							!showCols.contains(fld.getName)) { /* skip this column */ 
					} else {
						newFlds = fld :: newFlds
					}
				}
			}
			val allFlds = newFlds.toSeq ++: oldFlds
			val superClass: Class[_] = clazz.getSuperclass
//			val superClass: Class[_ >: T] = clazz.getSuperclass
//			superClass match {
//				case sc: Class[_ <: Record[K]] => loop(sc, allCols)
//				case _ => allCols
//			}
			if (null == superClass) allFlds else loop(superClass, allFlds, showCols)
		}
		loop(clazz, Nil, showCols)
	}


	def getTableName[T <: Record[K], K](clazz: Class[T], dialect: Dialect): Try[String] = {
		val tbl = clazz.getAnnotation(classOf[Table])
		if (null == tbl) Failure(new RuntimeException("Not Db Entry")) else {
			val database = if (isBlankStr(tbl.database)) "" else tbl.database
			val table = if (isBlankStr(tbl.table)) clazz.getName else tbl.table
			Success(dialect.sqlTableName(database, table))
		}
	}

	val paramRegex = """:([-_0-9a-zA-Z]+)""".r

	def parseValues(query: String, params: Map[String, Any]): Seq[Any] = {
		for (m <- paramRegex findAllMatchIn query) yield m.group(1)
	}.toSeq

	/* 替换查询语句中以冒号开头的参数名替换为sql标准中的问号参数 */
	def parseQuery(query: String): String = paramRegex.replaceAllIn(query, "?")

	def setValueInField(field: Field, obj: Object, value: Object): Unit = {
		field.setAccessible(true);
		val fieldType = field.getType
		fieldType match { // 特别处理一下Date类型，有时数据库会返回  long
			case t if t.isAssignableFrom(classOf[Date]) => value match {
				case o: java.lang.Long => field.set(obj, new Date(o))
				case o: java.util.Date => field.set(obj, o)
				case o => field.set(obj, o)
			}
			case t => field.set(obj, value)
		}
	}

	/* 设置sql中问号参数的值 */
	def setQueryValues(ps: PreparedStatement, values: Seq[Any]): PreparedStatement = {
		for (n <- 1 to values.size) {
			values(n - 1) match {
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
