package com.criteo.vizatra.vizsql

sealed trait Case { def format(str: String): String }
case object UpperCase extends Case { def format(str: String) = str.toUpperCase }
case object LowerCase extends Case { def format(str: String) = str.toLowerCase }
case object CamelCase extends Case {
  def format(str: String) = str.headOption.map(_.toString.toUpperCase).getOrElse("") + str.drop(1).toLowerCase
}

case class Style(pretty: Boolean, keywords: Case, identifiers: Case)
object Style {
  implicit val default = Style(true, UpperCase, LowerCase)
  val compact = Style(false, UpperCase, LowerCase)
}

sealed trait Show {
  def ~(o: Show) = Show.Group(this :: o :: Nil)
  def ~(o: Option[Show]) = o.map(o => Show.Group(this :: o :: Nil)).getOrElse(this)
  def ~-(o: Show) = Show.Group(this :: Show.Whitespace :: o :: Nil)
  def ~-(o: Option[Show]) = o.map(o => Show.Group(this :: Show.Whitespace :: o :: Nil)).getOrElse(this)
  def ~/(o: Show) = Show.Group(this :: Show.NewLine :: o :: Nil)
  def ~/(o: Option[Show]) = o.map(o => Show.Group(this :: Show.NewLine :: o :: Nil)).getOrElse(this)
  def ~|(o: Show*) = Show.Group(this :: Show.Indented(Show.Group(o.toList)) :: Nil)
  def ~|(o: Option[Show]) = o.map(o => Show.Group(this :: Show.Indented(Show.Group(List(o))) :: Nil)).getOrElse(this)

  def toSQL(style: Style): String = Show.toSQL(this, style, None).right.getOrElse(sys.error("WAT?"))
  def toSQL(style: Style, placeholders: Placeholders, namedParameters: Map[String,Any], anonymousParameters: List[Any]) = {
     Show.toSQL(this, style, Some((placeholders, namedParameters, anonymousParameters)))
  }
}

object Show {
  def toSQL(show: Show, style: Style, placeholders: Option[(Placeholders,Map[String,Any],List[Any])]): Either[Err,String] = {
    val INDENT = "  "
    case class MissingParameter(err: Err) extends Throwable
    def trimRight(parts: List[String]) = {
      val maybeT = parts.reverse.dropWhile(_ == INDENT)
      if(maybeT.headOption.exists(_ == "\n")) {
        maybeT.tail.reverse
      } else parts
    }
    var pIndex = 0
    def print(x: Show, indent: Int, parts: List[String]): List[String] = x match {
      case Keyword(k) => parts ++ (style.keywords.format(k) :: Nil)
      case Identifier(i) => parts ++ (style.identifiers.format(i) :: Nil)
      case Text(x) => parts ++ (x :: Nil)
      case Whitespace => parts ++ (" " :: Nil)
      case NewLine =>
        trimRight(parts) ++ ("\n" :: (0 until indent).map(_ => INDENT).toList)
      case Indented(group) =>
        print(NewLine, indent, trimRight(
          print(group.copy(items = NewLine :: group.items), indent + 1, parts)
        ))
      case Group(items) =>
        items.foldLeft(parts) {
          case (parts, i) => print(i, indent, parts)
        }
      case Parameter(placeholder) =>
        placeholders.map {
          case (placeholders, namedParameters, anonymousParameters) =>
            def param(paramType: Option[Type], value: Any): String = paramType.map {
              case STRING(_) => value match {
                case x :: _ => param(paramType, x)
                case s: String => s"'${value.toString.replace("'", "''")}'"
                case x => throw new MissingParameter(ParameterError(
                  s"unexpected value $x (${x.getClass.getName}) for an SQL STRING parameter", placeholder.pos
                ))
              }
              case INTEGER(_) => value match {
                case x :: _ => param(paramType, x)
                case x: Int => x.toString
                case x: Long => x.toInt.toString
                case x: String => try {
                  x.toInt.toString
                } catch {
                  case _: Throwable => throw new MissingParameter(ParameterError(
                    s"unexpected value $x (${x.getClass.getName}) for an SQL INTEGER parameter", placeholder.pos
                  ))
                }
                case x => throw new MissingParameter(ParameterError(
                  s"unexpected value $x (${x.getClass.getName}) for an SQL INTEGER parameter", placeholder.pos
                ))
              }
              case DATE(_) => value match {
                case x :: _ => param(paramType, x)
                case x: String => s"'${value.toString.replace("'", "''")}'"
                case x => throw new MissingParameter(ParameterError(
                  s"unexpected value $x (${x.getClass.getName}) for an SQL DATE parameter", placeholder.pos
                ))
              }
              case TIMESTAMP(_) => value match {
                case x :: _ => param(paramType, x)
                case x: String => s"'${value.toString.replace("'", "''")}'"
                case x => throw new MissingParameter(ParameterError(
                  s"unexpected value $x (${x.getClass.getName}) for an SQL TIMESTAMP parameter", placeholder.pos
                ))
              }
              case SET(t) => value match {
                case x: Seq[_] => "(" + x.map(param(Some(t), _)).mkString(", ") + ")"
                case x => throw new MissingParameter(ParameterError(
                  s"unexpected value $x (${x.getClass.getName}) for an SQL SET parameter", placeholder.pos
                ))
              }
              case RANGE(t) => value match {
                case (a,b) => param(Some(t), a) + " AND " + param(Some(t), b)
                case a :: b :: _ => param(Some(t), a) + " AND " + param(Some(t), b)
                case x => throw new MissingParameter(ParameterError(
                  s"unexpected value $x (${x.getClass.getName}) for an SQL RANGE parameter", placeholder.pos
                ))
              }
            }.getOrElse {
              throw new MissingParameter(ParameterError(
                "unresolver parameter", placeholder.pos
              ))
            }
            placeholder.name match {
              case Some(key) if namedParameters.contains(key) =>
                parts ++ (param(placeholders.find(_._1.name.exists(_ == key)).map(_._2), namedParameters(key)) :: Nil)
              case None if pIndex < anonymousParameters.size =>
                val s = param(placeholders.filter(_._1.name.isDefined).drop(pIndex).headOption.map(_._2), anonymousParameters(pIndex))
                pIndex = pIndex + 1
                parts ++ (s :: Nil)
              case x =>
                throw new MissingParameter(ParameterError(
                  s"""missing value for parameter ${placeholder.name.getOrElse("")}""", placeholder.pos
                ))
            }
        }.getOrElse {
          parts ++ (s"""?${placeholder.name.getOrElse("")}""" :: Nil)
        }
    }
    try {
      Right(print(show, 0, Nil).mkString.trim)
    } catch {
      case MissingParameter(err) => Left(err)
    }
  }

  case class Keyword(keyword: String) extends Show
  case class Identifier(identifier: String) extends Show
  case class Text(chars: String) extends Show
  case class Indented(group: Group) extends Show
  case class Parameter(placeholder: Placeholder) extends Show
  case class Group(items: List[Show]) extends Show
  case object Whitespace extends Show
  case object NewLine extends Show

  def line = NewLine
  def nest(show: Show*) = Indented(Group(show.toList))
  def keyword(str: String) = Keyword(str)
  def ident(str: String) = Identifier(str)
  def join(items: List[Show], separator: Show) = {
    Group(items.dropRight(1).flatMap(_ :: separator :: Nil) ++ items.lastOption.map(_ :: Nil).getOrElse(Nil))
  }
  def ~?(placeholder: Placeholder) = Parameter(placeholder)

  implicit def toText(str: String) = Text(str)
}