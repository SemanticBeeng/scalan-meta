/**
 * User: Alexander Slesarenko
 * Date: 11/16/13
 */
package scalan.meta

object Extensions {
  implicit class IterableExtensions[A](val it: Iterable[A]) extends AnyVal
  {
    def opt(show: Iterable[A] => String = _.mkString, default: String = ""): String = 
      if (it.isEmpty) default else show(it)

    def rep(show: A => String = _.toString, sep: String = ", "): String = it.map(show).mkString(sep)

    def enumTypes(show: Int => String) = (1 to it.size).map(show)
  }

  def all[A](a: A): String = a.toString

  implicit class OptionExtensions[A](val opt: Option[A]) extends AnyVal {
    def opt(show: A => String = _.toString, default: String = ""): String = opt match {
      case None => default
      case Some(a) => show(a)
    }
  }
  implicit class BooleanExtensions[A](val opt: Boolean) extends AnyVal {
    def opt(show: => String, default: => String = ""): String = if(opt) show else default
  }
}

trait ScalanCodegen extends ScalanAst with ScalanParsers { ctx: EntityManagement =>

  class EntityFileGenerator(module: SEntityModuleDef) {
    import Extensions._

    def getEntityTemplateData(e: STraitDef) = {
      val tyArgs = e.tpeArgs.map(_.name)
      (e.name,
        e.tpeArgs,
        tyArgs.rep(t => t),
        tyArgs.rep(t =>s"$t:Elem")
        )
    }

    def getSClassTemplateData(c: SClassDef) = {
      val tyArgs = c.tpeArgs.map(_.name)
      (c.name,
      tyArgs.opt(tyArgs => s"[${tyArgs.rep(t => t)}]"),
      tyArgs.opt(tyArgs => s"[${tyArgs.rep(t =>s"$t:Elem")}]"),
      c.args.map(a => a.name),
      c.args.map(a => s"${a.name}: ${a.tpe}"),
      c.args.map(a => a.tpe match {
        case STraitCall("Rep", List(t)) => t
        case _ => sys.error(s"Invalid field $a. Fields of concrete classes should be of type Rep[T] for some T.")
      }),
      c.ancestors.head
      )
    }

    def dataType(ts: List[STpeExpr]): String = ts match {
      case Nil => "Unit"
      case t :: Nil => t.toString
      case t :: ts => s"($t, ${dataType(ts)})"
    }
    
    def pairify(fs: List[String]): String = fs match {
      case Nil => "unit"
      case f :: Nil => f
      case f :: fs => s"Pair($f, ${pairify(fs)})"
    }
    
    def zeroSExpr(t: STpeExpr): String = t match {
      case STpeInt => 0.toString
      case STpeBoolean => false.toString
      case STpeFloat => 0f.toString
      case STpeString => "\"\""
      case STraitCall(name, args) => s"element[$t].defaultRepValue"
      case STpeTuple(items) => pairify(items.map(zeroSExpr))
      case STpeFunc(domain, range) => s"""fun { (x: Rep[${domain}]) => ${zeroSExpr(range)} }"""
      case t => throw new IllegalArgumentException(s"Can't generate zero value for $t")
    }
    
    lazy val (entityName, tyArgs, types, typesWithElems) = getEntityTemplateData(module.entityOps)
    
    def getTraitAbs = {
      val companionName = s"${entityName}Companion"
      val proxy =
        s"""
        |  // single proxy for each type family
        |  implicit def proxy$entityName[$typesWithElems](p: Rep[$entityName[$types]]): $entityName[$types] =
        |    proxyOps[$entityName[$types]](p)
        |""".stripMargin

      val familyElem = s"""  trait ${entityName}Elem[From,To] extends ViewElem[From, To]""".stripMargin
      val defaultElem = s"""
        |  // implicit def default${entityName}Elem[$typesWithElems]: Elem[$entityName[$types]] = ???
        |""".stripMargin
      val companionElem = s"""
        |  trait ${companionName}Elem extends CompanionElem[${companionName}Abs]
        |  implicit lazy val ${companionName}Elem: ${companionName}Elem = new ${companionName}Elem {
        |    lazy val tag = typeTag[${companionName}Abs]
        |    lazy val defaultRep = Default.defaultVal($entityName)
        |  }
        |
        |  trait ${companionName}Abs extends ${companionName}
        |  def $entityName: Rep[${companionName}Abs]
        |  implicit def proxy$companionName(p: Rep[${companionName}]): ${companionName} = {
        |    proxyOps[${companionName}](p, true)
        |  }
        |""".stripMargin
        
      val isoZero = "defaultRepTo"
      val defaultVal = "Default.defaultVal"

      val defs = for { c <- module.concreteSClasses } yield {
        val (className, types, typesWithElems, fields, fieldsWithType, fieldTypes, traitWithTypes) = getSClassTemplateData(c)
        val implicitArgs = c.implicitArgs.opt(args => s"(implicit ${args.rep(a => s"${a.name}: ${a.tpe}")})")
        val useImplicits = c.implicitArgs.opt(args => s"(${args.map(_.name).rep()})")
        s"""
        |  // elem for concrete class
        |  class ${className}Elem${types}(implicit iso: Iso[${className}Data${types}, $className${types}]) extends ${entityName}Elem[${className}Data${types}, $className${types}]
        |
        |  // state representation type
        |  type ${className}Data${types} = ${dataType(fieldTypes)}
        |
        |  // 3) Iso for concrete class
        |  class ${className}Iso${types}${implicitArgs}
        |    extends Iso[${className}Data${types}, $className${types}] {
        |    override def from(p: Rep[$className${types}]) =
        |      unmk${className}(p) match {
        |        case Some((${fields.opt(fields => fields.rep(), "unit")})) => ${pairify(fields)}
        |        case None => !!!
        |      }
        |    override def to(p: Rep[${dataType(fieldTypes)}]) = {
        |      val ${pairify(fields)} = p
        |      $className(${fields.rep()})
        |    }
        |    lazy val tag = {
        |${c.tpeArgs.rep(a => s"      implicit val tag${a.name} = element[${a.name}].tag", "\n")}
        |      typeTag[$className${types}]
        |    }
        |    lazy val ${isoZero} = $defaultVal[Rep[$className${types}]]($className(${fieldTypes.rep(zeroSExpr(_))}))
        |    lazy val eTo = new ${className}Elem${types}()(this)
        |  }
        |  // 4) constructor and deconstructor
        |  trait ${className}CompanionAbs extends ${className}Companion {
        |${(fields.length != 1).opt(s"""
        |    def apply${types}(p: Rep[${className}Data${types}])${implicitArgs}: Rep[$className${types}] =
        |      iso$className${useImplicits}.to(p)""".stripMargin)}
        |    def apply${types}(${fieldsWithType.rep()})${implicitArgs}: Rep[$className${types}] =
        |      mk$className(${fields.rep()})
        |    def unapply${typesWithElems}(p: Rep[$className${types}]) = unmk$className(p)
        |  }
        |  def $className: Rep[${className}CompanionAbs]
        |  implicit def proxy${className}Companion(p: Rep[${className}CompanionAbs]): ${className}CompanionAbs = {
        |    proxyOps[${className}CompanionAbs](p, true)
        |  }
        |
        |  trait ${className}CompanionElem extends CompanionElem[${className}CompanionAbs]
        |  implicit lazy val ${className}CompanionElem: ${className}CompanionElem = new ${className}CompanionElem {
        |    lazy val tag = typeTag[${className}CompanionAbs]
        |    lazy val defaultRep = Default.defaultVal($className)
        |  }
        |
        |  implicit def proxy$className${typesWithElems}(p: Rep[$className${types}]): ${className}${types} = {
        |    proxyOps[${className}${types}](p)
        |  }
        |
        |  implicit class Extended$className${types}(p: Rep[$className${types}])${implicitArgs} {
        |    def toData: Rep[${className}Data${types}] = iso$className${useImplicits}.from(p)
        |  }
        |
        |  // 5) implicit resolution of Iso
        |  implicit def iso$className${types}${implicitArgs}: Iso[${className}Data${types}, $className${types}] =
        |    new ${className}Iso${types}
        |
        |  // 6) smart constructor and deconstructor
        |  def mk$className${types}(${fieldsWithType.rep()})${implicitArgs}: Rep[$className${types}]
        |  def unmk$className${typesWithElems}(p: Rep[$className${types}]): Option[(${fieldTypes.opt(fieldTypes => fieldTypes.rep(t => s"Rep[$t]"), "Rep[Unit]")})]
        |""".stripMargin
      }

      s"""
       |trait ${module.name}Abs extends ${module.name}
       |{ ${module.selfType.opt(t => s"self: ${t.tpe} =>")}
       |$proxy
       |$familyElem
       |$companionElem
       |${defs.mkString("\n")}
       |}
       |""".stripMargin
    }

    def getSClassSeq(c: SClassDef) = {
      val (className, types, typesWithElems, fields, fieldsWithType, _, traitWithTypes) = getSClassTemplateData(c)
      val implicitArgs = c.implicitArgs.opt(args => s"(implicit ${args.rep(a => s"${a.name}: ${a.tpe}")})")
      val implicitSignature = c.implicitArgs.opt(args => s"(implicit ${args.rep(a => s"override val ${a.name}: ${a.tpe}")})")
      val userTypeDefs =
        s"""
         |  case class Seq$className${types}
         |      (${fieldsWithType.rep(f => s"override val $f")})
         |      ${implicitSignature}
         |    extends $className${types}(${fields.rep()})${c.selfType.opt(t => s" with ${t.tpe}")} with UserTypeSeq[$traitWithTypes, $className${types}] {
         |    lazy val selfType = element[${className}${types}].asInstanceOf[Elem[$traitWithTypes]]
         |  }
         |  lazy val $className = new ${className}CompanionAbs with UserTypeSeq[${className}CompanionAbs, ${className}CompanionAbs] {
         |    lazy val selfType = element[${className}CompanionAbs]
         |  }""".stripMargin

      val constrDefs =
        s"""
         |  def mk$className${types}
         |      (${fieldsWithType.rep()})$implicitArgs =
         |      new Seq$className${types}(${fields.rep()})${c.selfType.opt(t => s" with ${t.tpe}")}
         |  def unmk$className${typesWithElems}(p: Rep[$className${types}]) =
         |    Some((${fields.rep(f => s"p.$f")}))
         |""".stripMargin

      s"""$userTypeDefs\n$constrDefs"""
    }

    def getSClassExp(c: SClassDef) = {
      val (className, types, typesWithElems, fields, fieldsWithType, fieldTypes, traitWithTypes) = getSClassTemplateData(c)
      val implicitArgs = c.implicitArgs.opt(args => s"(implicit ${args.rep(a => s"${a.name}: ${a.tpe}")})")
      val implicitSignature = c.implicitArgs.opt(args => s"(implicit ${args.rep(a => s"override val ${a.name}: ${a.tpe}")})")
      val userTypeNodeDefs =
        s"""
         |  case class Exp$className${types}
         |      (${fieldsWithType.rep(f => s"override val $f")})
         |      ${implicitSignature}
         |    extends $className${types}(${fields.rep()})${c.selfType.opt(t => s" with ${t.tpe}")} with UserTypeDef[$traitWithTypes, $className${types}] {
         |    lazy val selfType = element[$className${types}].asInstanceOf[Elem[$traitWithTypes]]
         |    override def mirror(t: Transformer) = Exp$className${types}(${fields.rep(f => s"t($f)")})
         |  }
         |
         |  lazy val $className: Rep[${className}CompanionAbs] = new ${className}CompanionAbs with UserTypeDef[${className}CompanionAbs, ${className}CompanionAbs] {
         |    lazy val selfType = element[${className}CompanionAbs]
         |    override def mirror(t: Transformer) = this
         |  }
         |""".stripMargin

      val constrDefs =
        s"""
         |  def mk$className${types}
         |    (${fieldsWithType.rep()})$implicitArgs =
         |    new Exp$className${types}(${fields.rep()})${c.selfType.opt(t => s" with ${t.tpe}")}
         |  def unmk$className${typesWithElems}(p: Rep[$className${types}]) =
         |    Some((${fields.rep(f => s"p.$f")}))
         |""".stripMargin

      s"""$userTypeNodeDefs\n$constrDefs"""
    }

    def getTraitSeq = {
      val e = module.entityOps
      val (entityName, _, _, _) = getEntityTemplateData(e)
      val defs = for { c <- module.concreteSClasses } yield getSClassSeq(c)

      s"""
       |trait ${module.name}Seq extends ${module.name}Abs { self: ${config.seqContextTrait}${module.selfType.opt(t => s" with ${t.tpe}")} =>
       |  lazy val $entityName: Rep[${entityName}CompanionAbs] = new ${entityName}CompanionAbs with UserTypeSeq[${entityName}CompanionAbs, ${entityName}CompanionAbs] {
       |    lazy val selfType = element[${entityName}CompanionAbs]
       |  }
       |${defs.mkString("\n")}
       |}
       |""".stripMargin
    }

    def getTraitExp = {
      val e = module.entityOps
      val (entityName, _, _, _) = getEntityTemplateData(e)
      val defs = for { c <- module.concreteSClasses } yield getSClassExp(c)

      s"""
       |trait ${module.name}Exp extends ${module.name}Abs { self: ${config.stagedContextTrait}${module.selfType.opt(t => s" with ${t.tpe}")} =>
       |  lazy val $entityName: Rep[${entityName}CompanionAbs] = new ${entityName}CompanionAbs with UserTypeDef[${entityName}CompanionAbs, ${entityName}CompanionAbs] {
       |    lazy val selfType = element[${entityName}CompanionAbs]
       |    override def mirror(t: Transformer) = this
       |  }
       |${defs.mkString("\n")}
       |}
       |""".stripMargin
    }

    def getFileHeader = {
      s"""
      |package ${module.packageName}
      |package impl
      |
      |${(module.imports ++ config.extraImports.map(SImportStat(_))).rep(i => s"import ${i.name}", "\n")}
      |""".stripMargin
    }

    def getImplFile: String = {
      val topLevel = List(
        getFileHeader,
        getTraitAbs,
        getTraitSeq,
        getTraitExp
      )
      topLevel.mkString("\n")
    }
  }
}
