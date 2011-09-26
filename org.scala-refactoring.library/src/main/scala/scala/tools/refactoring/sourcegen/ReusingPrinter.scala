/*
 * Copyright 2005-2010 LAMP/EPFL
 */

package scala.tools.refactoring
package sourcegen

import tools.nsc.util.RangePosition

trait ReusingPrinter extends TreePrintingTraversals with AbstractPrinter {

  outer: LayoutHelper with common.Tracing with common.PimpedTrees with common.CompilerAccess with Indentations =>

  import global._
  
  object reusingPrinter extends TreePrinting with PrintingUtils
    with MiscPrinters 
    with MethodCallPrinters
    with WhilePrinters  
    with PatternMatchingPrinters
    with TypePrinters
    with FunctionPrinters 
    with ImportPrinters  
    with PackagePrinters 
    with TryThrowPrinters
    with ClassModulePrinters
    with IfPrinters
    with ValDefDefPrinters
    with SuperPrinters
    with BlockPrinters
    with LiteralPrinters {
    
    override def dispatchToPrinter(t: Tree, ctx: PrintingContext): Fragment = {
      
      val originalIndentation = outer.indentation(t)
      
      val newCtx = ctx.copy(ind = ctx.ind.setTo(originalIndentation))
            
      val (leadingParent, trailingParent) = surroundingLayoutFromParentsAndSiblings(t)
  
      val printedFragment = if(ctx.changeSet hasChanged t) {
        super.dispatchToPrinter(t, newCtx)
      } else if (t.pos.isTransparent) {
        trace("Not in change set but transparent, continue printing...")
        /*
         * If we have a position that is not in the changeset, we can stop printing
         * and just use the existing source code. But there are potentially many
         * trees with the same transparent position besides a non-transparent range,
         * so we need to look further until we find that non-transparent range and
         * can take its source code.
         * 
         * */
        super.dispatchToPrinter(t, newCtx)
      } else {
        trace("Not in change set, keep original code.")
        Fragment(t.pos.source.content.slice(t.pos.start, t.pos.end).mkString)
      }
        
      val indentedFragment = {
        if(ctx.ind.needsToBeFixed(originalIndentation, leadingParent, l(newCtx), r(newCtx), trailingParent)) {
          val indentedLeadingLayout = ctx.ind.fixIndentation(leadingParent.asText, originalIndentation)
          val indentedCode = ctx.ind.fixIndentation(printedFragment.asText, originalIndentation)
          Fragment(indentedLeadingLayout, indentedCode, trailingParent)
        } else 
          Fragment(leadingParent, printedFragment.toLayout, trailingParent)    
      } \\ (trace("Result "+ t.getClass.getSimpleName +": %s", _))
      
      indentedFragment
    }
  }

  trait PrintingUtils {
    this: TreePrinting =>
    
    implicit def allowSurroundingWhitespace(s: String) = Requisite.allowSurroundingWhitespace(s)
    
    def newline(implicit ctx: PrintingContext) = Requisite.newline(ctx.ind.current, NL)
    
    def indentation(implicit ctx: PrintingContext) = ctx.ind.current

    def l(implicit ctx: PrintingContext) = leadingLayoutForTree(ctx.parent)
    
    def r(implicit ctx: PrintingContext) = trailingLayoutForTree(ctx.parent)
    
    def orig(tree: Tree): Tree = findOriginalTree(tree) getOrElse {
      trace("Original tree not found for %s, returning EmptyTree.", tree)  
      EmptyTree
    }
    
    /**
     * Returns a NameTree for a tree's name and gives it the position of
     * the original tree's name.
     */
    def nameOf(tree: Tree): Tree = {
      outer.NameTree(tree.nameString) setPos orig(tree).namePosition
    }
    
    /**
     * Prints the children of the tree, surrounded with the layout from
     * the existing code.
     */
    def printChildren(tree: Tree)(implicit ctx: PrintingContext) = {
      l ++ children(tree).foldLeft(EmptyFragment: Fragment)(_ ++ p(_)) ++ r
    }
    
    /**
     * This is the default handler that is called for non-overriden methods.
     */
    override def default(tree: Tree)(implicit ctx: PrintingContext): Fragment = {
      printChildren(tree)
    }
  }

  trait WhilePrinters {
    this: TreePrinting with PrintingUtils =>

    override def LabelDef(tree: LabelDef, name: Name, params: List[Tree], rhs: Tree)(implicit ctx: PrintingContext) = {

      val labelName = nameOf(tree)
    
      rhs match {
        case Block(stats, If(cond, _, _)) =>
          l ++ pp(stats) ++ p(labelName) ++ Layout("(") ++ p(cond) ++ r
        
        case If(cond, Block((body: Block) :: Nil, _), _) =>
          l ++ p(labelName) ++ Layout("(") ++ p(cond) ++ Layout(")") ++ p(body) ++ r
        
        case If(cond, then, _) =>
          l ++ p(labelName) ++ Layout("(") ++ p(cond) ++ Layout(")") ++ pi(then) ++ r        
      }
    }
  }

  trait PatternMatchingPrinters {
    this: TreePrinting with PrintingUtils =>

    override def CaseDef(tree: CaseDef, pat: Tree, guard: Tree, body: Tree)(implicit ctx: PrintingContext) = {
      body match {
       
        case b @ BlockExtractor(body) if !b.hasExistingCode =>
          val x = (l ++ p(pat) ++ p(guard)) ++ "=>"
          x ++ Fragment(NL + indentation) ++ ppi(body, separator = newline) ++ r
          
        case _ =>
          printChildren(tree)
      }
    }

    override def Alternative(tree: Alternative, trees: List[Tree])(implicit ctx: PrintingContext) = {
      l ++ pp(trees, separator = "|") ++ r
    }

    override def Bind(tree: Bind, name: Name, body: Tree)(implicit ctx: PrintingContext) = {
      val nameOrig = nameOf(tree)
      
      body match {
      
        case body: Bind =>
          l ++ p(nameOrig) ++ p(body, before = "(", after = ")") ++ r
          
        case _ =>
          l ++ p(nameOrig) ++ p(body) ++ r    
      }
    }

    override def UnApply(tree: UnApply, fun: Tree, args: List[Tree])(implicit ctx: PrintingContext) = {
      l ++ p(fun) ++ pp(args, separator = ", ", before = "(", after = ")") ++ r
    }

    override def Match(tree: Match, selector: Tree, cases: List[Tree])(implicit ctx: PrintingContext) = {
      if (keepTree(selector)) {
        l ++ p(selector) ++ " match" ++ pp(cases) ++ r        
      } else {
        l ++ pp(cases) ++ r        
      }
    }
  }

  trait MethodCallPrinters {
    this: TreePrinting with PrintingUtils =>

    override def Select(tree: Select, qualifier: Tree, selector: Name)(implicit ctx: PrintingContext) = {
      
      lazy val nameOrig = nameOf(tree)

      val ll = l
      
      qualifier match {
             
        case _ if selector.toString == "<init>" => 
          l ++ p(qualifier) ++ r
          
        case _: This if qualifier.pos == NoPosition => 
          l ++ Fragment(tree.symbol.nameString) ++ r
          
        // skip <init> from constructor calls
        case _: New if tree.symbol.isConstructor =>
          l ++ p(qualifier) ++ r
          
        case _ if tree.pos.sameRange(qualifier.pos) 
            && (selector.toString == "unapply" || selector.toString == "apply" || selector.toString == "unapplySeq") =>
          l ++ p(qualifier) ++ r
          
        case _: Apply if selector.toString.startsWith("unary_") =>
          
          val printedQualifier = p(qualifier)
          
          if(printedQualifier.asText.contains(" ")) //XXX better check to see if we need to print parens
            l ++ p(nameOrig) ++ "(" ++ printedQualifier ++ ")" ++ r          
          else
            l ++ p(nameOrig) ++ printedQualifier ++ r
          
        case _ if selector.toString.startsWith("unary_") =>
          l ++ p(nameOrig) ++ p(qualifier) ++ r
          
        case Apply(s @ global.Select(qual, name), Nil) =>
          
          val sName = nameOf(s)
          val ll = between(qual, sName)(s.pos.source)
          
          val _qualifier = p(qualifier)
          
          if(!_qualifier.asText.matches("^\\s*\\(.*\\)\\s*") && ll.contains(" ")) {
            l ++ "(" ++ _qualifier ++ ")" ++ " " ++ p(nameOrig) ++ r
          } else {
            l ++ _qualifier ++ p(nameOrig) ++ r
          }
          
        case _ =>

          val _q = p(qualifier)
          val _n = p(nameOrig)
  
          def hasNoSeparator = {
            val between = (_q.trailing ++ _n.leading).asText
            !between.contains(" ") && !between.contains(".")
          }
          
          def startsWithChar = _q.asText.matches(".*[a-zA-Z0-9]$")
          def endsWithChar   = _n.asText.matches("^[a-zA-Z0-9].*")
          
          def qualifierHasNoDot = qualifier match {
            case Apply(s @ global.Select(qual, name), _) if s.pos.isRange && qual.pos.isRange => 
              
              val sn: global.Name = s.name
              val nt = new NameTree(sn).setPos(s.namePosition)
                          
              val b = between(qual, nt)(qual.pos.source)
              !b.contains(".")
            case _ => false
          }
          
          def hasClosingParensBetweenQualifierAndSelector = {
            qualifier.pos.isRange && nameOrig.pos.isRange && {
              between(qualifier, nameOrig)(tree.pos.source).contains(")")
            }
          }
          
          if(startsWithChar && endsWithChar && hasNoSeparator) {
            l ++ _q ++ " " ++ _n ++ r
          } else if (qualifierHasNoDot && _n.leading.contains(".")) {
            l ++ "(" ++ _q ++ ")" ++ _n ++ r
          } else if (hasClosingParensBetweenQualifierAndSelector) {
            l ++ "(" ++ _q ++ ")" ++ _n ++ r
          } else {
            l ++ _q ++ _n ++ r
          }
      }
    }

    override def TypeApply(tree: TypeApply, fun: Tree, args: List[Tree])(implicit ctx: PrintingContext) = {
      fun match {
        
        case global.Select(fun @ global.Select(ths: global.This, _), _) if ths.pos == NoPosition => 
          l ++ p(fun) ++ pp(args) ++ r
          
        case _ => 
          l ++ p(fun) ++ pp(args, separator = ", ") ++ r    
      }
    }

    override def Apply(tree: Apply, fun: Tree, args: List[Tree])(implicit ctx: PrintingContext) = {
      
      (fun, args) match {
        
        case (global.Select(select: Select, nme.update), args) if fun.pos == select.pos && args.size > 0 =>
          
          args match {
            case arg :: Nil =>
              l ++ p(select) ++ p(arg) ++ r
              
            case _ =>
              val updateArgs = args.init
              val rhs = args.last
              l ++ p(select) ++ "(" ++ pp(updateArgs, separator = ", ") ++ ")" ++ " = " ++ p(rhs) ++ r  
          }

        // handle e.g. a += 1 which is a = (a + 1)
        case (_: Select, ((arg1: Apply) :: _)) if tree.pos.sameRange(arg1.pos) && arg1.pos.isTransparent =>
          l ++ p(fun) ++ between(fun, arg1.args.head)(tree.pos.source) ++ pp(arg1.args) ++ r
          
        // x :: xs in pattern match:
        case (EmptyTree, ((_: Bind) :: ( _: Bind) :: _)) if tree.tpe.toString.contains("::") =>
          l ++ pp(args) ++ r
          
        case (_, ((_: Bind) :: ( _: Bind) :: _)) =>
          l ++ p(fun) ++ pp(args, before = if(l contains "(") NoRequisite else "(", separator = ", ", after = ")") ++ r
          
        case (fun: Select, arg :: Nil) if 
            (fun.qualifier != EmptyTree && keepTree(fun.qualifier)) /*has receiver*/
             || fun.name.toString.endsWith("$eq") /*assigns*/ =>
          if(r.contains(")")) {
            l ++ p(fun) ++ "(" ++ p(arg) ++ r
          } else {
            l ++ p(fun) ++ p(arg) ++ r
          }
          
        case (TypeApply(_: Select, _), (arg @ Function(_, _: Match)) :: Nil) =>
          l ++ p(fun) ++ p(arg) ++ r
          
        case (TypeApply(receiver: Select, _), arg :: Nil) if !arg.isInstanceOf[Function] =>
          if(keepTree(receiver.qualifier) && !l.contains("(") && !r.contains(")"))  {
            l ++ p(fun) ++ p(arg) ++ r
          } else {
            l ++ p(fun) ++ p(arg, before = Requisite.anywhere("("), after = Requisite.anywhere(")")) ++ r
          }
          
        case (fun, arg :: Nil) if !keepTree(fun) =>
          l ++ p(arg) ++ r
          
        case (EmptyTree, args) =>
          l ++ pp(args, separator = ", ", before = "(", after = ")")  ++ r 
          
        /* Workaround for for-comprehensions. Because they are not represented with
         * their own ASTs, we sometimes need to work around some issues. This is for
         * the following case:
         * 
         *   for(`arg` <- `fun`) yield body
         * 
         * We discover this pattern by the transparent function with a position
         * smaller than the preceding (in the AST) Apply call. */
        case (generator, (f @ Function(arg :: _, body)) :: Nil) 
          if f.pos.isTransparent && 
             arg.pos.startOrPoint < generator.pos.startOrPoint &&
             between(arg, generator)(tree.pos.source).contains("<-") => 
               
          /* We only regenerate the code of the generator and the body, this will fail
           * to pick up any changes in the `arg`! 
           * 
           * Generic layout handling will remove a closing `)`, so we re-add it */
          l ++ p(generator, after = ")") ++ p(body) ++ r
           
        case (fun, Nil) =>
          
          // Calls to methods without `()` are represented by a select and no apply. 
          if(r.matches("""^\s*\)""")) {
            l ++ p(fun) ++ "(" ++ r
          } else {            
            l ++ p(fun) ++ r
          }
          
        case (fun, args) =>
          l ++ p(fun) ++ pp(args, separator = ("," ++ Requisite.Blank), before = "(", after = Requisite.anywhere(")"))  ++ r
      }
    }
  }

  trait TypePrinters {
    this: TreePrinting with PrintingUtils =>

    override def TypeTree(tree: TypeTree)(implicit ctx: PrintingContext) = {
      if (tree.original == null && !tree.pos.isTransparent) { 
        tree.tpe match {
          case ref @ RefinedType(_ :: parents, _) =>  
            l ++ Fragment(parents mkString " ") ++ r
          case t => 
            l ++ Fragment(t.toString) ++ r
        }
        
      } else {
        tree.tpe match {
          case typeRef @ TypeRef(tpe, sym, parents) if definitions.isFunctionType(typeRef) && !parents.isEmpty =>
            l ++ typeToString(tree, typeRef) ++ r
          case _ => 
            l ++ p(tree.original) ++ r
        }
      }
    }

    override def TypeDef(tree: TypeDef, mods: List[ModifierTree], name: Name, tparams: List[Tree], rhs: Tree)(implicit ctx: PrintingContext) = {
      val nameTree = nameOf(tree)
      l ++ pp(mods ::: nameTree :: Nil, separator = Requisite.Blank) ++ pp(tparams) ++ p(rhs)  ++ r
    }
    
    override def SelectFromTypeTree(tree: SelectFromTypeTree, qualifier: Tree, selector: Name)(implicit ctx: PrintingContext) = {
      l ++ p(qualifier) ++ p(nameOf(tree)) ++ r      
    }
    
    override def SelfTypeTree(tree: SelfTypeTree, name: NameTree, types: List[Tree], orig: Tree)(implicit ctx: PrintingContext) = {
      l ++ p(name) ++ pp(types) ++ r
    }
  }

  trait FunctionPrinters {
    this: TreePrinting with PrintingUtils =>

    override def Function(tree: Function, vparams: List[ValDef], body: Tree)(implicit ctx: PrintingContext) = {
      body match {
      
        case b @ BlockExtractor(body) if !b.hasExistingCode =>
          l ++ pp(vparams) ++ (NL + indentation) ++ ppi(body, separator = newline) ++ r
          
        case _ =>
          printChildren(tree)
      }
    }
  }

  trait ImportPrinters {
    this: TreePrinting with PrintingUtils =>

    override def Import(tree: Import, expr: Tree, selectors: List[ImportSelectorTree])(implicit ctx: PrintingContext) = {
      if(selectors.size > 1) {
        l ++ "import " ++ p(expr) ++ "{" ++ pp(selectors, separator = ", ") ++ "}" ++ r
      } else {
        l ++ "import " ++ p(expr) ++ pp(selectors, separator = ", ") ++ r
      }    
    }
  }

  trait PackagePrinters {
    this: TreePrinting with PrintingUtils =>

    override def PackageDef(tree: PackageDef, pid: RefTree, stats: List[Tree])(implicit ctx: PrintingContext) = {
      l ++ pp(pid :: stats, separator = newline) ++ r
    }
  }

  trait TryThrowPrinters {
    this: TreePrinting with PrintingUtils =>

    override def Try(tree: Try, block: Tree, catches: List[Tree], finalizer: Tree)(implicit ctx: PrintingContext) = {
      block match {
        
        case b @ BlockExtractor(block) if !b.hasExistingCode =>
          l ++ indentation ++ ppi(block, separator = newline) ++ pp(catches) ++ p(finalizer) ++ r
     
        case _ =>
          printChildren(tree)
      }    
    }
  }

  trait ClassModulePrinters {
    this: TreePrinting with PrintingUtils =>

    override def ClassDef(tree: ClassDef, mods: List[ModifierTree], name: Name, tparams: List[Tree], impl: Template)(implicit ctx: PrintingContext) = {
      val className = if(tree.symbol.isAnonymousClass) 
        EmptyFragment 
      else 
        p(nameOf(tree))
      
      l ++ pp(mods) ++ (className) ++ pp(tparams) ++ p(impl) ++ r
    }

    override def ModuleDef(tree: ModuleDef, mods: List[ModifierTree], name: Name, impl: Template)(implicit ctx: PrintingContext) = {
      val nameTree = p(nameOf(tree))
      val impl_ = p(impl)
      if(nameTree.asText.endsWith(" ") && impl_.asText.startsWith(" ")) {
        l ++ pp(mods) ++ Layout(nameTree.asText.init) ++ impl_ ++ r
      } else {      
        l ++ pp(mods) ++ nameTree ++ impl_ ++ r
      }
    }

    override def Template(tree: Template, parents: List[Tree], self: Tree, body: List[Tree])(implicit ctx: PrintingContext) = {
      (tree, orig(tree)) match {
    
        case (t @ TemplateExtractor(params, earlyBody, parents, self, Nil), _) =>
          val _params = params.headOption map (pms => pp(pms, separator = ", ", before = "(", after = ")")) getOrElse EmptyFragment
          val _parents = pp(parents)
          l ++ _params ++ pp(earlyBody) ++ _parents ++ p(self) ++ r
        
        case (t @ TemplateExtractor(params, earlyBody, parents, self, body), o @ TemplateExtractor(_, _, _, origSelf, origBody)) =>
          
          lazy val isExistingBodyAllOnOneLine = {
            val tplStartLine = o.pos.source.offsetToLine(o.pos.start)
            val tplEndLine = o.pos.source.offsetToLine(o.pos.end)
            tplStartLine == tplEndLine
          }
          
          val params_ = params.headOption map (pms => pp(pms, separator = ", ", after = Requisite.anywhere(")"))) getOrElse EmptyFragment
          
          val preBody = l ++ params_ ++ pp(earlyBody) ++ pp(parents) ++ p(self)
          
          if(origBody.isEmpty && origSelf.isEmpty && !body.isEmpty) {
            val alreadyHasBodyInTheCode = r.matches("(?ms).*\\{.*\\}.*") 
            val trailingLayout = if(alreadyHasBodyInTheCode) NoLayout else r
            
            val openingBrace = " {"+ NL + indentation
            val closingBrace = NL + indentation +"}"
            val bodyResult = ppi(body, separator = newline)
            
            preBody ++ trailingLayout ++ openingBrace ++ bodyResult ++ closingBrace
          } else if (isExistingBodyAllOnOneLine) {
            preBody ++ ppi(body, separator = newline) ++ r
          } else {
            preBody ++ newline ++ ppi(body, separator = newline) ++ r
          }
      }
    }
  }

  trait IfPrinters {
    this: TreePrinting with PrintingUtils =>

    override def If(tree: If, cond: Tree, thenp: Tree, elsep: Tree)(implicit ctx: PrintingContext) = {
      
      val o = orig(tree).asInstanceOf[If]
      
          val _else = {
            
            /*
             * Printing the else branch is tricky because of how {} are handled in the AST,
             * but only if the else branch already existed:
             */
            val elseBranchAlreadyExisted = keepTree(o.elsep) && o.elsep.pos.isRange
            
            if(elseBranchAlreadyExisted) {
              
              val layout = between(o.thenp, o.elsep)(o.pos.source).asText
              val l = Requisite.anywhere(layout.replaceAll("(?ms)else\\s*?\r?\n\\s*$", "else "))
              
              val curlyBracesAlreadyExist = layout.contains("{")
              val originalElseHasNoBlock = !o.elsep.isInstanceOf[Block]
              
              elsep match {
                
                /*
                 * The existing else branch was enclosed by {} but contained only a single
                 * statement.
                 * */
                case BlockExtractor(body) if originalElseHasNoBlock && curlyBracesAlreadyExist =>
                  pp(body, before = l, separator = Requisite.newline(ctx.ind.current + ctx.ind.defaultIncrement, NL))
                
                /*
                 * If there was no block before and also no curly braces, we have to write
                 * them now (indirectly through the Block), but we don't want to add any
                 * indentation.
                 * */
                case elsep: Block =>
                  outer.print(elsep, ctx) ifNotEmpty (_ ++ (NoRequisite, l))
  
                /* If it's a single statements, we print it indented: */
                case _ => 
                  pi(elsep, before = Requisite.anywhere(layout))
              }
  
            } else {
              val l = newline ++ "else" ++ Requisite.newline(ctx.ind.current + ctx.ind.defaultIncrement, NL)
              pi(elsep, before = l)
            }
          }
          
          val _cond = p(cond, before = "(", after = Requisite.anywhere(")"))
          
          val _then = thenp match {
            case block: Block =>
              p(block)
            case _ if keepTree(o.thenp) && o.thenp.pos.isRange =>
              val layout = between(o.cond, o.thenp)(o.pos.source).asText
              val printedThen = pi(thenp)
              
              if(layout.contains("{") && !printedThen.asText.matches("(?ms)^\\s*\\{.*")) {
                val (left, right) = layout.splitAt(layout.indexOf(")") + 1)
                pi(thenp, before = Requisite.anywhere(right))
              } else {
                pi(thenp)
              }
              
            case _ => 
              pi(thenp)
          }
          
          l ++ _cond ++ _then ++ _else ++ r
    }
  }

  trait ValDefDefPrinters {
    this: TreePrinting with PrintingUtils =>

    override def ValDef(tree: ValDef, mods: List[ModifierTree], name: Name, tpt: Tree, rhs: Tree)(implicit ctx: PrintingContext) = {
      val nameTree = nameOf(tree)
      l ++ pp(mods ::: nameTree :: Nil, separator = Requisite.Blank) ++ p(tpt) ++ p(rhs) ++ r
    }

    override def DefDef(tree: DefDef, mods: List[ModifierTree], name: Name, tparams: List[Tree], vparamss: List[List[ValDef]], tpt: Tree, rhs: Tree)(implicit ctx: PrintingContext) = {
      val nameTree = nameOf(tree)
      val modsAndName = pp(mods ::: nameTree :: Nil, separator = Requisite.Blank)
      val parameters     = vparamss.map(vparams => pp(vparams, before = "(", separator = ", ", after = Requisite.anywhere(")"))).foldLeft(EmptyFragment: Fragment)(_ ++ _) 
      val typeParameters = pp(tparams, before = "[", separator = ", ", after = Requisite.anywhere("]"))
      val body = p(rhs)
      
      val resultType = {
        val colon = new Requisite {
          def isRequired(l: Layout, r: Layout) = {
            !(l.contains(":") || r.contains(":"))
          }
          def getLayout = Layout(": ")
        }
        p(tpt, before = colon)
      }
            
      def hasEqualInSource = {
        val originalDefDef = orig(tree)
        (originalDefDef :: children(originalDefDef)).filter(_.pos.isRange).reverse match {
          case last :: secondlast :: _ =>
            between(secondlast, last)(last.pos.source).contains("=")
          case _ => false
        }
      }
      
      val noEqualNeeded = {
        body == EmptyFragment || rhs.tpe == null || (rhs.tpe != null && rhs.tpe.toString == "Unit")
      }
      
      if(noEqualNeeded && !hasEqualInSource) {
        l ++ modsAndName ++ typeParameters ++ parameters ++ resultType ++ body ++ r
      } else {
        
        val equals = new Requisite {
          def isRequired(l: Layout, r: Layout) = {
            !(l.contains("=") || r.contains("="))
          }
          def getLayout = Layout(" = ")
        }
        
        l ++ modsAndName ++ typeParameters ++ parameters ++ resultType ++ equals ++ body ++ r
      }
    }
  }

  trait SuperPrinters {
    this: TreePrinting with PrintingUtils =>

    override def SuperConstructorCall(tree: SuperConstructorCall, clazz: global.Tree, args: List[global.Tree])(implicit ctx: PrintingContext) = {
      val after: Requisite = if(r.contains(")")) NoRequisite else ")"
      l ++ p(clazz) ++ pp(args, separator = ", ", before = "(", after = after) ++ r
    }

    override def Super(tree: Super, qual: Tree, mix: Name)(implicit ctx: PrintingContext) = {
      
      // duplicate of pretty printer!
      val q = qual match {
        case This(qual: Name) if qual.toString == "" => EmptyFragment
        case This(qual: Name) => Fragment(qual.toString + ".")
        case _ => p(qual)
      }
      
      val m = if(mix.toString == "") "" else "["+ mix + "]"
      
      l ++ q ++ Fragment("super"+ m) ++ r      
      
    }
  }

  trait LiteralPrinters {
    this: TreePrinting with PrintingUtils =>
    
    override def Literal(tree: Literal, value: Constant)(implicit ctx: PrintingContext) = {
      if(value.tag == StringTag) {
        val escaped = value.stringValue.replace("""\""", """\\""")
        l ++ Fragment("\""+ escaped +"\"")  ++ r
      } else if (value.isNumeric) {
        Fragment((l ++ layout(tree.pos.start, tree.pos.end)(tree.pos.source) ++ r).asText)
      } else if (charAtTreeStartPos(tree) == Some('{') && charBeforeTreeEndPos(tree) == Some('}')) {
        /*
         * Scala 2.9:
         * 
         * Empty RHS of DefDefs are Literals
         * */
        trace("Literal tree is empty { }")
        Fragment((l ++ layout(tree.pos.start, tree.pos.end)(tree.pos.source) ++ r).asText)
      } else { 
        l ++ Fragment(value.stringValue) ++ r
      }
    }
  
     def charAtTreeStartPos(t: Tree) = t.pos match {
        case range: RangePosition => Some(t.pos.source.content(t.pos.start))
        case _ => None
      }
      
      def charBeforeTreeEndPos(t: Tree) = t.pos match {
        case range: RangePosition => Some(t.pos.source.content(t.pos.end - 1))
        case _ => None
      }
  }

  trait BlockPrinters {
    this: TreePrinting with PrintingUtils =>
    
    override def Block(tree: Block, stats: List[Tree])(implicit ctx: PrintingContext) = {
       
      def allTreesOnSameLine(ts: List[Tree]): Boolean = {
        val poss = ts map (_.pos)
        poss.forall(_.isRange) && (poss.map(_.line).distinct.length <= 1)
      }
      
      if(stats.size > 1 && allTreesOnSameLine(stats)) {
        l ++ pp(stats) ++ r
      } else {
        val rest = ppi(stats, separator = newline) ++ r 
        if(l.contains("{") && !stats.head.hasExistingCode)
          l ++ Requisite.newline(ctx.ind.current, NL, force = true) ++ rest
        else 
          l ++ rest
      }
    }
  }

  trait MiscPrinters {
    this: TreePrinting with PrintingUtils =>

    override def Assign(tree: Assign, lhs: Tree, rhs: Tree)(implicit ctx: PrintingContext) = {
      l ++ p(lhs, after = "=") ++ p(rhs) ++ r
    }
    
    override def MultipleAssignment(tree: MultipleAssignment, extractor: Tree, values: List[ValDef], rhs: Tree)(implicit ctx: PrintingContext) = {
      extractor match {
        case EmptyTree =>
          l ++ pp(values, separator = ", ") ++ ")" ++ p(rhs) ++ r
        case _ =>
          l ++ "val " ++ p(extractor) ++ " = " ++ p(rhs) ++ r
      }
    }

    override def New(tree: New, tpt: Tree)(implicit ctx: PrintingContext) = {
      if (tree.pos.start > tree.pos.point) {
        l ++ p(tpt) ++ r
      } else {
        Fragment("new") ++ l ++ p(tpt) ++ r
      }
    }

    override def This(tree: This, qual: Name)(implicit ctx: PrintingContext) = {
      l ++ Fragment((if(qual.toString == "") "" else qual +".") + "this") ++ r
    }

    override def Ident(tree: Ident, name: Name)(implicit ctx: PrintingContext) = {
      l ++ Fragment(tree.nameString) ++ r
    }

    override def ModifierTree(tree: ModifierTree, flag: Long)(implicit ctx: PrintingContext) = {
      l ++ Fragment(tree.nameString) ++ r
    }
    
    override def NameTree(tree: Tree)(implicit ctx: PrintingContext) = {
      if (tree.pos.isTransparent) {
        l ++ EmptyFragment ++ r
      } else {
        l ++ Fragment(tree.nameString) ++ r
      }
    }
  }
}
