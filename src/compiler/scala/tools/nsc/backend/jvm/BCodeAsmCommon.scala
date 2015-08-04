/* NSC -- new Scala compiler
 * Copyright 2005-2014 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package backend.jvm

import scala.tools.nsc.Global
import scala.tools.nsc.backend.jvm.BTypes.{InternalName, MethodInlineInfo, InlineInfo}
import BackendReporting.ClassSymbolInfoFailureSI9111
import scala.tools.asm

/**
 * This trait contains code shared between GenBCode and GenASM that depends on types defined in
 * the compiler cake (Global).
 */
final class BCodeAsmCommon[G <: Global](val global: G) {
  import global._
  import definitions._

  val ExcludedForwarderFlags = {
    import scala.tools.nsc.symtab.Flags._
    // Should include DEFERRED but this breaks findMember.
    SPECIALIZED | LIFTED | PROTECTED | STATIC | EXPANDEDNAME | BridgeAndPrivateFlags | MACRO
  }

  /**
   * True for classes generated by the Scala compiler that are considered top-level in terms of
   * the InnerClass / EnclosingMethod classfile attributes. See comment in BTypes.
   */
  def considerAsTopLevelImplementationArtifact(classSym: Symbol) = {
    classSym.isImplClass || classSym.isSpecialized
  }

  /**
   * Cache the value of delambdafy == "inline" for each run. We need to query this value many
   * times, so caching makes sense.
   */
  object delambdafyInline {
    private var runId = -1
    private var value = false

    def apply(): Boolean = {
      if (runId != global.currentRunId) {
        runId = global.currentRunId
        value = settings.Ydelambdafy.value == "inline"
      }
      value
    }
  }

  /**
   * True if `classSym` is an anonymous class or a local class. I.e., false if `classSym` is a
   * member class. This method is used to decide if we should emit an EnclosingMethod attribute.
   * It is also used to decide whether the "owner" field in the InnerClass attribute should be
   * null.
   */
  def isAnonymousOrLocalClass(classSym: Symbol): Boolean = {
    assert(classSym.isClass, s"not a class: $classSym")
    val r = exitingPickler(classSym.isAnonymousClass) || !classSym.originalOwner.isClass
    if (r && settings.Ybackend.value == "GenBCode") {
      // this assertion only holds in GenBCode. lambda lift renames symbols and may accidentally
      // introduce `$lambda` into a class name, making `isDelambdafyFunction` true. under GenBCode
      // we prevent this, see `nonAnon` in LambdaLift.
      // phase travel necessary: after flatten, the name includes the name of outer classes.
      // if some outer name contains $lambda, a non-lambda class is considered lambda.
      assert(exitingPickler(!classSym.isDelambdafyFunction), classSym.name)
    }
    r
  }

  /**
   * The next enclosing definition in the source structure. Includes anonymous function classes
   * under delambdafy:inline, even though they are only generated during UnCurry.
   */
  def nextEnclosing(sym: Symbol): Symbol = {
    val origOwner = sym.originalOwner
    // phase travel necessary: after flatten, the name includes the name of outer classes.
    // if some outer name contains $anon, a non-anon class is considered anon.
    if (delambdafyInline() && sym.rawowner.isAnonymousFunction) {
      // SI-9105: special handling for anonymous functions under delambdafy:inline.
      //
      //   class C { def t = () => { def f { class Z } } }
      //
      //   class C { def t = byNameMethod { def f { class Z } } }
      //
      // In both examples, the method f lambda-lifted into the anonfun class.
      //
      // In both examples, the enclosing method of Z is f, the enclosing class is the anonfun.
      // So nextEnclosing needs to return the following chain:  Z - f - anonFunClassSym - ...
      //
      // In the first example, the initial owner of f is a TermSymbol named "$anonfun" (note: not the anonFunClassSym!)
      // In the second, the initial owner of f is t (no anon fun term symbol for by-name args!).
      //
      // In both cases, the rawowner of class Z is the anonFunClassSym. So the check in the `if`
      // above makes sure we don't jump over the anonymous function in the by-name argument case.
      //
      // However, we cannot directly return the rawowner: if `sym` is Z, we need to include method f
      // in the result. This is done by comparing the rawowners (read: lambdalift-targets) of `sym`
      // and `sym.originalOwner`: if they are the same, then the originalOwner is "in between", and
      // we need to return it.
      // If the rawowners are different, the symbol was not in between. In the first example, the
      // originalOwner of `f` is the anonfun-term-symbol, whose rawowner is C. So the nextEnclosing
      // of `f` is its rawowner, the anonFunClassSym.
      //
      // In delambdafy:method we don't have that problem. The f method is lambda-lifted into C,
      // not into the anonymous function class. The originalOwner chain is Z - f - C.
      if (sym.originalOwner.rawowner == sym.rawowner) sym.originalOwner
      else sym.rawowner
    } else {
      origOwner
    }
  }

  def nextEnclosingClass(sym: Symbol): Symbol = {
    if (sym.isClass) sym
    else nextEnclosingClass(nextEnclosing(sym))
  }

  def classOriginallyNestedInClass(nestedClass: Symbol, enclosingClass: Symbol) ={
    nextEnclosingClass(nextEnclosing(nestedClass)) == enclosingClass
  }

  /**
   * Returns the enclosing method for non-member classes. In the following example
   *
   * class A {
   *   def f = {
   *     class B {
   *       class C
   *     }
   *   }
   * }
   *
   * the method returns Some(f) for B, but None for C, because C is a member class. For non-member
   * classes that are not enclosed by a method, it returns None:
   *
   * class A {
   *   { class B }
   * }
   *
   * In this case, for B, we return None.
   *
   * The EnclosingMethod attribute needs to be added to non-member classes (see doc in BTypes).
   * This is a source-level property, so we need to use the originalOwner chain to reconstruct it.
   */
  private def enclosingMethodForEnclosingMethodAttribute(classSym: Symbol): Option[Symbol] = {
    assert(classSym.isClass, classSym)

    def doesNotExist(method: Symbol) = {
      // (1) SI-9124, some trait methods don't exist in the generated interface. see comment in BTypes.
      // (2) Value classes. Member methods of value classes exist in the generated box class. However,
      //     nested methods lifted into a value class are moved to the companion object and don't exist
      //     in the value class itself. We can identify such nested methods: the initial enclosing class
      //     is a value class, but the current owner is some other class (the module class).
      method.owner.isTrait && method.isImplOnly || { // (1)
        val enclCls = nextEnclosingClass(method)
        exitingPickler(enclCls.isDerivedValueClass) && method.owner != enclCls // (2)
      }
    }

    def enclosingMethod(sym: Symbol): Option[Symbol] = {
      if (sym.isClass || sym == NoSymbol) None
      else if (sym.isMethod) {
        if (doesNotExist(sym)) None else Some(sym)
      }
      else enclosingMethod(nextEnclosing(sym))
    }
    enclosingMethod(nextEnclosing(classSym))
  }

  /**
   * The enclosing class for emitting the EnclosingMethod attribute. Since this is a source-level
   * property, this method looks at the originalOwner chain. See doc in BTypes.
   */
  private def enclosingClassForEnclosingMethodAttribute(classSym: Symbol): Symbol = {
    assert(classSym.isClass, classSym)
    val r = nextEnclosingClass(nextEnclosing(classSym))
    // this should be an assertion, but we are more cautious for now as it was introduced before the 2.11.6 minor release
    if (considerAsTopLevelImplementationArtifact(r)) devWarning(s"enclosing class of $classSym should not be an implementation artifact class: $r")
    r
  }

  final case class EnclosingMethodEntry(owner: String, name: String, methodDescriptor: String)

  /**
   * Data for emitting an EnclosingMethod attribute. None if `classSym` is a member class (not
   * an anonymous or local class). See doc in BTypes.
   *
   * The class is parametrized by two functions to obtain a bytecode class descriptor for a class
   * symbol, and to obtain a method signature descriptor fro a method symbol. These function depend
   * on the implementation of GenASM / GenBCode, so they need to be passed in.
   */
  def enclosingMethodAttribute(classSym: Symbol, classDesc: Symbol => String, methodDesc: Symbol => String): Option[EnclosingMethodEntry] = {
    // trait impl classes are always top-level, see comment in BTypes
    if (isAnonymousOrLocalClass(classSym) && !considerAsTopLevelImplementationArtifact(classSym)) {
      val enclosingClass = enclosingClassForEnclosingMethodAttribute(classSym)
      val methodOpt = enclosingMethodForEnclosingMethodAttribute(classSym) match {
        case some @ Some(m) =>
          if (m.owner != enclosingClass) {
            // This should never happen. In case it does, it prevents emitting an invalid
            // EnclosingMethod attribute: if the attribute specifies an enclosing method,
            // it needs to exist in the specified enclosing class.
            devWarning(s"the owner of the enclosing method ${m.locationString} should be the same as the enclosing class $enclosingClass")
            None
          } else some
        case none => none
      }
      Some(EnclosingMethodEntry(
        classDesc(enclosingClass),
        methodOpt.map(_.javaSimpleName.toString).orNull,
        methodOpt.map(methodDesc).orNull))
    } else {
      None
    }
  }

  /**
   * This is basically a re-implementation of sym.isStaticOwner, but using the originalOwner chain.
   *
   * The problem is that we are interested in a source-level property. Various phases changed the
   * symbol's properties in the meantime, mostly lambdalift modified (destructively) the owner.
   * Therefore, `sym.isStatic` is not what we want. For example, in
   *   object T { def f { object U } }
   * the owner of U is T, so UModuleClass.isStatic is true. Phase travel does not help here.
   */
  def isOriginallyStaticOwner(sym: Symbol): Boolean = {
    sym.isPackageClass || sym.isModuleClass && isOriginallyStaticOwner(sym.originalOwner)
  }

  /**
   * Reconstruct the classfile flags from a Java defined class symbol.
   *
   * The implementation of this method is slightly different that `javaFlags` in BTypesFromSymbols.
   * The javaFlags method is primarily used to map Scala symbol flags to sensible classfile flags
   * that are used in the generated classfiles. For example, all classes emitted by the Scala
   * compiler have ACC_PUBLIC.
   *
   * When building a [[ClassBType]] from a Java class symbol, the flags in the type's `info` have
   * to correspond exactly to the flags in the classfile. For example, if the class is package
   * protected (i.e., it doesn't have the ACC_PUBLIC flag), this needs to be reflected in the
   * ClassBType. For example, the inliner needs the correct flags for access checks.
   *
   * Class flags are listed here:
   *   https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.1-200-E.1
   */
  def javaClassfileFlags(classSym: Symbol): Int = {
    assert(classSym.isJava, s"Expected Java class symbol, got ${classSym.fullName}")
    import asm.Opcodes._
    def enumFlags = ACC_ENUM | {
      // Java enums have the `ACC_ABSTRACT` flag if they have a deferred method.
      // We cannot trust `hasAbstractFlag`: the ClassfileParser adds `ABSTRACT` and `SEALED` to all
      // Java enums for exhaustiveness checking.
      val hasAbstractMethod = classSym.info.decls.exists(s => s.isMethod && s.isDeferred)
      if (hasAbstractMethod) ACC_ABSTRACT else 0
    }
    GenBCode.mkFlags(
      // SI-9393: the classfile / java source parser make java annotation symbols look like classes.
      // here we recover the actual classfile flags.
      if (classSym.hasJavaAnnotationFlag)                        ACC_ANNOTATION | ACC_INTERFACE | ACC_ABSTRACT else 0,
      if (classSym.isPublic)                                     ACC_PUBLIC    else 0,
      if (classSym.isFinal)                                      ACC_FINAL     else 0,
      // see the link above. javac does the same: ACC_SUPER for all classes, but not interfaces.
      if (classSym.isInterface)                                  ACC_INTERFACE else ACC_SUPER,
      // for Java enums, we cannot trust `hasAbstractFlag` (see comment in enumFlags)
      if (!classSym.hasJavaEnumFlag && classSym.hasAbstractFlag) ACC_ABSTRACT  else 0,
      if (classSym.isArtifact)                                   ACC_SYNTHETIC else 0,
      if (classSym.hasJavaEnumFlag)                              enumFlags     else 0
    )
  }

  /**
   * The member classes of a class symbol. Note that the result of this method depends on the
   * current phase, for example, after lambdalift, all local classes become member of the enclosing
   * class.
   *
   * Impl classes are always considered top-level, see comment in BTypes.
   */
  def memberClassesForInnerClassTable(classSymbol: Symbol): List[Symbol] = classSymbol.info.decls.collect({
    case sym if sym.isClass && !considerAsTopLevelImplementationArtifact(sym) =>
      sym
    case sym if sym.isModule && !considerAsTopLevelImplementationArtifact(sym) => // impl classes get the lateMODULE flag in mixin
      val r = exitingPickler(sym.moduleClass)
      assert(r != NoSymbol, sym.fullLocationString)
      r
  })(collection.breakOut)

  lazy val AnnotationRetentionPolicyModule       = AnnotationRetentionPolicyAttr.companionModule
  lazy val AnnotationRetentionPolicySourceValue  = AnnotationRetentionPolicyModule.tpe.member(TermName("SOURCE"))
  lazy val AnnotationRetentionPolicyClassValue   = AnnotationRetentionPolicyModule.tpe.member(TermName("CLASS"))
  lazy val AnnotationRetentionPolicyRuntimeValue = AnnotationRetentionPolicyModule.tpe.member(TermName("RUNTIME"))

  /** Whether an annotation should be emitted as a Java annotation
    * .initialize: if 'annot' is read from pickle, atp might be uninitialized
    */
  def shouldEmitAnnotation(annot: AnnotationInfo) = {
    annot.symbol.initialize.isJavaDefined &&
      annot.matches(ClassfileAnnotationClass) &&
      retentionPolicyOf(annot) != AnnotationRetentionPolicySourceValue &&
      annot.args.isEmpty
  }

  def isRuntimeVisible(annot: AnnotationInfo): Boolean = {
    annot.atp.typeSymbol.getAnnotation(AnnotationRetentionAttr) match {
      case Some(retentionAnnot) =>
        retentionAnnot.assocs.contains(nme.value -> LiteralAnnotArg(Constant(AnnotationRetentionPolicyRuntimeValue)))
      case _ =>
        // SI-8926: if the annotation class symbol doesn't have a @RetentionPolicy annotation, the
        // annotation is emitted with visibility `RUNTIME`
        true
    }
  }

  private def retentionPolicyOf(annot: AnnotationInfo): Symbol =
    annot.atp.typeSymbol.getAnnotation(AnnotationRetentionAttr).map(_.assocs).flatMap(assoc =>
      assoc.collectFirst {
        case (`nme`.value, LiteralAnnotArg(Constant(value: Symbol))) => value
      }).getOrElse(AnnotationRetentionPolicyClassValue)

  def implementedInterfaces(classSym: Symbol): List[Symbol] = {
    // Additional interface parents based on annotations and other cues
    def newParentForAnnotation(ann: AnnotationInfo): Option[Type] = ann.symbol match {
      case RemoteAttr => Some(RemoteInterfaceClass.tpe)
      case _          => None
    }

    // SI-9393: java annotations are interfaces, but the classfile / java source parsers make them look like classes.
    def isInterfaceOrTrait(sym: Symbol) = sym.isInterface || sym.isTrait || sym.hasJavaAnnotationFlag

    val classParents = {
      val parents = classSym.info.parents
      // SI-9393: the classfile / java source parsers add Annotation and ClassfileAnnotation to the
      // parents of a java annotations. undo this for the backend (where we need classfile-level information).
      if (classSym.hasJavaAnnotationFlag) parents.filterNot(c => c.typeSymbol == ClassfileAnnotationClass || c.typeSymbol == AnnotationClass)
      else parents
    }

    val allParents = classParents ++ classSym.annotations.flatMap(newParentForAnnotation)

    // We keep the superClass when computing minimizeParents to eliminate more interfaces.
    // Example: T can be eliminated from D
    //   trait T
    //   class C extends T
    //   class D extends C with T
    val interfaces = erasure.minimizeParents(allParents) match {
      case superClass :: ifs if !isInterfaceOrTrait(superClass.typeSymbol) =>
        ifs
      case ifs =>
        // minimizeParents removes the superclass if it's redundant, for example:
        //  trait A
        //  class C extends Object with A  // minimizeParents removes Object
        ifs
    }
    interfaces.map(_.typeSymbol)
  }

  /**
   * This is a hack to work around SI-9111. The completer of `methodSym` may report type errors. We
   * cannot change the typer context of the completer at this point and make it silent: the context
   * captured when creating the completer in the namer. However, we can temporarily replace
   * global.reporter (it's a var) to store errors.
   */
  def completeSilentlyAndCheckErroneous(sym: Symbol): Boolean = {
    if (sym.hasCompleteInfo) false
    else {
      val originalReporter = global.reporter
      val storeReporter = new reporters.StoreReporter()
      global.reporter = storeReporter
      try {
        sym.info
      } finally {
        global.reporter = originalReporter
      }
      sym.isErroneous
    }
  }

  /**
   * Build the [[InlineInfo]] for a class symbol.
   */
  def buildInlineInfoFromClassSymbol(classSym: Symbol, classSymToInternalName: Symbol => InternalName, methodSymToDescriptor: Symbol => String): InlineInfo = {
    val traitSelfType = if (classSym.isTrait && !classSym.isImplClass) {
      // The mixin phase uses typeOfThis for the self parameter in implementation class methods.
      val selfSym = classSym.typeOfThis.typeSymbol
      if (selfSym != classSym) Some(classSymToInternalName(selfSym)) else None
    } else {
      None
    }

    val isEffectivelyFinal = classSym.isEffectivelyFinal

    var warning = Option.empty[ClassSymbolInfoFailureSI9111]

    // Primitive methods cannot be inlined, so there's no point in building a MethodInlineInfo. Also, some
    // primitive methods (e.g., `isInstanceOf`) have non-erased types, which confuses [[typeToBType]].
    val methodInlineInfos = classSym.info.decls.iterator.filter(m => m.isMethod && !scalaPrimitives.isPrimitive(m)).flatMap({
      case methodSym =>
        if (completeSilentlyAndCheckErroneous(methodSym)) {
          // Happens due to SI-9111. Just don't provide any MethodInlineInfo for that method, we don't need fail the compiler.
          if (!classSym.isJavaDefined) devWarning("SI-9111 should only be possible for Java classes")
          warning = Some(ClassSymbolInfoFailureSI9111(classSym.fullName))
          None
        } else {
          val name      = methodSym.javaSimpleName.toString // same as in genDefDef
          val signature = name + methodSymToDescriptor(methodSym)

          // Some detours are required here because of changing flags (lateDEFERRED, lateMODULE):
          // 1. Why the phase travel? Concrete trait methods obtain the lateDEFERRED flag in Mixin.
          //    This makes isEffectivelyFinalOrNotOverridden false, which would prevent non-final
          //    but non-overridden methods of sealed traits from being inlined.
          // 2. Why the special case for `classSym.isImplClass`? Impl class symbols obtain the
          //    lateMODULE flag during Mixin. During the phase travel to exitingPickler, the late
          //    flag is ignored. The members are therefore not isEffectivelyFinal (their owner
          //    is not a module). Since we know that all impl class members are static, we can
          //    just take the shortcut.
          val effectivelyFinal = classSym.isImplClass || exitingPickler(methodSym.isEffectivelyFinalOrNotOverridden)

          // Identify trait interface methods that have a static implementation in the implementation
          // class. Invocations of these methods can be re-wrired directly to the static implementation
          // if they are final or the receiver is known.
          //
          // Using `erasure.needsImplMethod` is not enough: it keeps field accessors, module getters
          // and super accessors. When AddInterfaces creates the impl class, these methods are
          // initially added to it.
          //
          // The mixin phase later on filters out most of these members from the impl class (see
          // Mixin.isImplementedStatically). However, accessors for concrete lazy vals remain in the
          // impl class after mixin. So the filter in mixin is not exactly what we need here (we
          // want to identify concrete trait methods, not any accessors). So we check some symbol
          // properties manually.
//          val traitMethodWithStaticImplementation = {
//            import symtab.Flags._
//            classSym.isTrait && !classSym.isImplClass &&
//              erasure.needsImplMethod(methodSym) &&
//              !methodSym.isModule &&
//              !(methodSym hasFlag (ACCESSOR | SUPERACCESSOR))
//          }

          val info = MethodInlineInfo(
            effectivelyFinal                    = effectivelyFinal,
            traitMethodWithStaticImplementation = false, //traitMethodWithStaticImplementation,
            annotatedInline                     = methodSym.hasAnnotation(ScalaInlineClass),
            annotatedNoInline                   = methodSym.hasAnnotation(ScalaNoInlineClass)
          )
          Some((signature, info))
        }
    }).toMap

    InlineInfo(traitSelfType, isEffectivelyFinal, methodInlineInfos, warning)
  }
}

object BCodeAsmCommon {
  /**
   * Valid flags for InnerClass attribute entry.
   * See http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.6
   */
  val INNER_CLASSES_FLAGS = {
    asm.Opcodes.ACC_PUBLIC   | asm.Opcodes.ACC_PRIVATE   | asm.Opcodes.ACC_PROTECTED  |
      asm.Opcodes.ACC_STATIC   | asm.Opcodes.ACC_FINAL     | asm.Opcodes.ACC_INTERFACE  |
      asm.Opcodes.ACC_ABSTRACT | asm.Opcodes.ACC_SYNTHETIC | asm.Opcodes.ACC_ANNOTATION |
      asm.Opcodes.ACC_ENUM
  }
}
