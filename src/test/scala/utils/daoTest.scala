package jadeutils.comm.dao

import jadeutils.common._

import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSuite
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class SessionTest extends FunSuite with Logging {

	class TestTransaction(id: String) extends Transaction with Logging {
		def isActive() = { "active" == status }

		def begin() { status = "active" }
		def commit() { status = "commited" }
		def rollback() { status = "rollbacked" }
	}

	class TestSession(id: String) extends DaoSession with Logging {
		private[this] var transCount = 0

		def isOpen() = "open" == status

		def open() { status = "open"; logTrace("Session {} open", id) }

		def close() { status = "closed"; logTrace("Session {} close", id) }

		def getTransaction() = if (null != trans) trans else {
			trans = new TestTransaction("" + transCount)
			transCount = transCount + 1
			trans
		}
	}

	class TestSessionFactory extends DaoSessionFactory with Logging {
		private[this] var sessCount = 0

		def createSession = if (null != session) session else {
			session = new TestSession("" + sessCount)
			sessCount = sessCount + 1
			session
		}
	}

	object TestSessionFactoryHelper extends DaoSessionFactoryHelper {

		def initSessionFactory = new TestSessionFactory

	}

	class TestBaseService extends BaseTransactionService {
		val sfHelper = TestSessionFactoryHelper
	}

	class User(val id: Int, val name: String) {
		override def toString: String = "{%d, %s}".format(id, name)
	}

	class UserDao(session: DaoSession) extends BasicDao[User, Int](session) 
		with Logging
	{

		def getById(id: Int): User = {
			logDebug("before query")

			val u = if (id > 0) new User(id, "TestUser" + id)
			else throw new java.lang.RuntimeException("Exception for Text")

			logDebug("after query")
			u
		}

		def insert(model: User)  {
			logDebug("before insert")
			if (null == model) 
				throw new java.lang.RuntimeException("Exception for Text")
			logDebug("after insert")
		}

	}

	object UserService extends TestBaseService {
		private val dao = new UserDao(getSession)

		def getUser(id: Int): User = withTransaction { dao.getById(id) }

		def insertUser(user: User) { withTransaction { dao.insert(user) } }
	}


	test("Test-Trans-get-commit") {
		logInfo("\n\n======== test get commit =============")
		val u = UserService.getUser(33)
		logDebug("{}", u)
	}
	test("Test-Trans-insert-commit") {
		logInfo("\n\n======== test insert commit =============")
		UserService.insertUser(new User(33, "Testuser33"))
	}

	test("Test-Trans-get-rollback") {
		logInfo("\n\n======== test get rollback =============")
		intercept[java.lang.Exception] {
			val u = UserService.getUser(-33)
			logDebug("{}", u)
		}
	}

	test("Test-Trans-insert-rollback") {
		logInfo("\n\n======== test insert rollback =============")
		intercept[java.lang.Exception] {
			UserService.insertUser(null)
		}
	}

}