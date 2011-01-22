/* sbt -- Simple Build Tool
 * Copyright 2009, 2010  Mark Harrah
 */
package sbt

	import java.io.File
	import complete.{DefaultParsers, Parser}
	import CommandSupport.logger

sealed trait Command {
	def help: Seq[Help]
	def parser: State => Parser[State]
	def tags: AttributeMap
	def tag[T](key: AttributeKey[T], value: T): Command
}
private[sbt] final class SimpleCommand(val name: String, val help: Seq[Help], val parser: State => Parser[State], val tags: AttributeMap) extends Command {
	assert(Command validID name, "'" + name + "' is not a valid command name." )
	def tag[T](key: AttributeKey[T], value: T): SimpleCommand = new SimpleCommand(name, help, parser, tags.put(key, value))
}
private[sbt] final class ArbitraryCommand(val parser: State => Parser[State], val help: Seq[Help], val tags: AttributeMap) extends Command
{
	def tag[T](key: AttributeKey[T], value: T): ArbitraryCommand = new ArbitraryCommand(parser, help, tags.put(key, value))
}

object Command
{
	def pointer(s: String, i: Int): String  =  (s take i) map { case '\t' => '\t'; case _ => ' ' } mkString;
	
		import DefaultParsers._

	val Logged = AttributeKey[Logger]("log")
	val HistoryPath = SettingKey[Option[File]]("history")
	val Analysis = AttributeKey[inc.Analysis]("analysis")
	val Watch = SettingKey[Watched]("continuous-watch")

	def command(name: String)(f: State => State): Command  =  command(name, Nil)(f)
	def command(name: String, briefHelp: String, detail: String)(f: State => State): Command  =  command(name, Help(name, (name, briefHelp), detail) :: Nil)(f)
	def command(name: String, help: Seq[Help])(f: State => State): Command  =  apply(name, help : _*)(state => success(f(state)))

	def apply(name: String, briefHelp: (String, String), detail: String)(parser: State => Parser[State]): Command =
		apply(name, Help(name, briefHelp, detail) )(parser)
	def apply(name: String, help: Help*)(parser: State => Parser[State]): Command  =  new SimpleCommand(name, help, parser, AttributeMap.empty)

	def args(name: String, briefHelp: (String, String), detail: String, display: String)(f: (State, Seq[String]) => State): Command =
		args(name, display, Help(name, briefHelp, detail) )(f)
	
	def args(name: String, display: String, help: Help*)(f: (State, Seq[String]) => State): Command =
		apply(name, help : _*)( state => spaceDelimited(display) map f.curried(state) )

	def single(name: String, briefHelp: (String, String), detail: String)(f: (State, String) => State): Command =
		single(name, Help(name, briefHelp, detail) )(f)
	def single(name: String, help: Help*)(f: (State, String) => State): Command =
		apply(name, help : _*)( state => token(any.+.string map f.curried(state)) )
	
	def custom(parser: State => Parser[State], help: Seq[Help]): Command  =  new ArbitraryCommand(parser, help, AttributeMap.empty)

	def validID(name: String) =
		Parser(OpOrID)(name).resultEmpty.isDefined
	
	def combine(cmds: Seq[Command]): State => Parser[State] =
	{
		val (simple, arbs) = separateCommands(cmds)
		state => (simpleParser(simple)(state) /: arbs.map(_ parser state) ){ _ | _ }
	}
	private[this] def separateCommands(cmds: Seq[Command]): (Seq[SimpleCommand], Seq[ArbitraryCommand]) =
		Collections.separate(cmds){ case s: SimpleCommand => Left(s); case a: ArbitraryCommand => Right(a) }

	def simpleParser(cmds: Seq[SimpleCommand]): State => Parser[State] =
		simpleParser(cmds.map(sc => (sc.name, sc.parser)).toMap )

	def simpleParser(commandMap: Map[String, State => Parser[State]]): State => Parser[State] =
		(state: State) => token(OpOrID examples commandMap.keys.toSet) flatMap { id =>
			(commandMap get id) match { case None => failure("No command named '" + id + "'"); case Some(c) => c(state) }
		}
		
	def process(command: String, state: State): State =
	{
		val parser = combine(state.processors)
		Parser.result(parser(state), command) match
		{
			case Right(s) => s
			case Left((msg,pos)) =>
				val errMsg = commandError(command, msg, pos)
				logger(state).info(errMsg)
				state.fail				
		}
	}
	def commandError(command: String, msg: String, index: Int): String =
	{
		val (line, modIndex) = extractLine(command, index)
		msg + "\n" + line + "\n" + pointer(msg, modIndex)
	}
	def extractLine(s: String, i: Int): (String, Int) =
	{
		val notNewline = (c: Char) => c != '\n' && c != '\r'
		val left = takeRightWhile( s.substring(0, i) )( notNewline )
		val right = s substring i takeWhile notNewline
		(left + right, left.length)
	}
	def takeRightWhile(s: String)(pred: Char => Boolean): String =
	{
		def loop(i: Int): String =
			if(i < 0)
				s
			else if( pred(s(i)) )
				loop(i-1)
			else
				s.substring(i+1)
		loop(s.length - 1)
	}
}

trait Help
{
	def detail: (Set[String], String)
	def brief: (String, String)
}
object Help
{
	def apply(name: String, briefHelp: (String, String), detail: String): Help  =  apply(briefHelp, (Set(name), detail))

	def apply(briefHelp: (String, String), detailedHelp: (Set[String], String) = (Set.empty, "") ): Help =
		new Help { def detail = detailedHelp; def brief = briefHelp }
}
trait CommandDefinitions
{
	def commands: Seq[Command]
}
trait ReflectedCommands extends CommandDefinitions
{
	def commands = ReflectUtilities.allVals[Command](this).values.toSeq
}