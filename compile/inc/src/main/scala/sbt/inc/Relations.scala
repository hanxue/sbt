/* sbt -- Simple Build Tool
 * Copyright 2010  Mark Harrah
 */
package sbt
package inc

import java.io.File
import Relations.Source


/** Provides mappings between source files, generated classes (products), and binaries.
* Dependencies that are tracked include internal: a dependency on a source in the same compilation group (project),
* external: a dependency on a source in another compilation group (tracked as the name of the class),
* binary: a dependency on a class or jar file not generated by a source file in any tracked compilation group,
* inherited: a dependency that resulted from a public template inheriting,
* direct: any type of dependency, including inheritance. */
trait Relations
{
	/** All sources _with at least one product_ . */
	def allSources: collection.Set[File]
	
	/** All products associated with sources. */
	def allProducts: collection.Set[File]

	/** All files that are recorded as a binary dependency of a source file.*/
	def allBinaryDeps: collection.Set[File]

	/** All files in this compilation group (project) that are recorded as a source dependency of a source file in this group.*/
	def allInternalSrcDeps: collection.Set[File]

	/** All files in another compilation group (project) that are recorded as a source dependency of a source file in this group.*/
	def allExternalDeps: collection.Set[String]

	/** Fully qualified names of classes generated from source file `src`. */
	def classNames(src: File): Set[String]

	/** Source files that generated a class with the given fully qualified `name`. This is typically a set containing a single file. */
	def definesClass(name: String): Set[File]
	
	/** The classes that were generated for source file `src`. */
	def products(src: File): Set[File]
	/** The source files that generated class file `prod`.  This is typically a set containing a single file. */
	def produced(prod: File): Set[File]
	
	/** The binary dependencies for the source file `src`. */
	def binaryDeps(src: File): Set[File]
	/** The source files that depend on binary file `dep`. */
	def usesBinary(dep: File): Set[File]

	/** Internal source dependencies for `src`.  This includes both direct and inherited dependencies.  */
	def internalSrcDeps(src: File): Set[File]
	/** Internal source files that depend on internal source `dep`.  This includes both direct and inherited dependencies.  */
	def usesInternalSrc(dep: File): Set[File]
	
	/** External source dependencies that internal source file `src` depends on.  This includes both direct and inherited dependencies.  */
	def externalDeps(src: File): Set[String]
	/** Internal source dependencies that depend on external source file `dep`.  This includes both direct and inherited dependencies.  */
	def usesExternal(dep: String): Set[File]
	
	/** Records internal source file `src` as generating class file `prod` with top-level class `name`. */
	def addProduct(src: File, prod: File, name: String): Relations

	/** Records internal source file `src` as depending on class `dependsOn` in an external source file.
	* If `inherited` is true, this dependency is recorded as coming from a public template in `src` extending something in `dependsOn` (an inheritance dependency).
	* Whatever the value of `inherited`, the dependency is also recorded as a direct dependency. */
	def addExternalDep(src: File, dependsOn: String, inherited: Boolean): Relations

	/** Records internal source file `src` depending on a dependency binary dependency `dependsOn`.*/
	def addBinaryDep(src: File, dependsOn: File): Relations

	/** Records internal source file `src` as having direct dependencies on internal source files `directDependsOn`
	* and inheritance dependencies on `inheritedDependsOn`.  Everything in `inheritedDependsOn` must be included in `directDependsOn`;
	* this method does not automatically record direct dependencies like `addExternalDep` does.*/
	def addInternalSrcDeps(src: File, directDependsOn: Iterable[File], inheritedDependsOn: Iterable[File]): Relations

	/** Concatenates the two relations. Acts naively, i.e., doesn't internalize external deps on added files. */
	def ++ (o: Relations): Relations

	/** Drops all dependency mappings a->b where a is in `sources`. Acts naively, i.e., doesn't externalize internal deps on removed files. */
	def -- (sources: Iterable[File]): Relations

	@deprecated("OK to remove in 0.14", "0.13.1")
	def groupBy[K](f: (File => K)): Map[K, Relations]

	/** The relation between internal sources and generated class files. */	
	def srcProd: Relation[File, File]

	/** The dependency relation between internal sources and binaries. */	
	def binaryDep: Relation[File, File]

	/** The dependency relation between internal sources.  This includes both direct and inherited dependencies.*/
	def internalSrcDep: Relation[File, File]

	/** The dependency relation between internal and external sources.  This includes both direct and inherited dependencies.*/
	def externalDep: Relation[File, String]

	/** The dependency relations between sources.  These include both direct and inherited dependencies.*/
	def direct: Source

	/** The inheritance dependency relations between sources.*/
	def publicInherited: Source

	/** The relation between a source file and the fully qualified names of classes generated from it.*/
	def classes: Relation[File, String]
}


object Relations
{
	/** Tracks internal and external source dependencies for a specific dependency type, such as direct or inherited.*/
	final class Source private[sbt](val internal: Relation[File,File], val external: Relation[File,String]) {
		def addInternal(source: File, dependsOn: Iterable[File]): Source = new Source(internal + (source, dependsOn), external)
		def addExternal(source: File, dependsOn: String): Source = new Source(internal, external + (source, dependsOn))
		/** Drops all dependency mappings from `sources`. Acts naively, i.e., doesn't externalize internal deps on removed files.*/
		def --(sources: Iterable[File]): Source = new Source(internal -- sources, external -- sources)
		def ++(o: Source): Source = new Source(internal ++ o.internal, external ++ o.external)

		@deprecated("Broken implementation. OK to remove in 0.14", "0.13.1")
		def groupBySource[K](f: File => K): Map[K, Source] = {

			val i = internal.groupBy { case (a,b) => f(a) }
			val e = external.groupBy { case (a,b) => f(a) }
			val pairs = for( k <- i.keySet ++ e.keySet ) yield
				(k, new Source( getOrEmpty(i, k), getOrEmpty(e, k) ))
			pairs.toMap
		}

		override def equals(other: Any) = other match {
			case o: Source => internal == o.internal && external == o.external
			case _ => false
		}

		override def hashCode = (internal, external).hashCode
	}

	private[sbt] def getOrEmpty[A,B,K](m: Map[K, Relation[A,B]], k: K): Relation[A,B] = m.getOrElse(k, Relation.empty)

	private[this] lazy val e = Relation.empty[File, File]
	private[this] lazy val estr = Relation.empty[File, String]
	private[this] lazy val es = new Source(e, estr)

	def emptySource: Source = es
	def empty: Relations = new MRelations(e, e, es, es, estr)

	def make(srcProd: Relation[File, File], binaryDep: Relation[File, File], direct: Source, publicInherited: Source, classes: Relation[File, String]): Relations =
		new MRelations(srcProd, binaryDep, direct = direct, publicInherited = publicInherited, classes)
	def makeSource(internal: Relation[File,File], external: Relation[File,String]): Source = new Source(internal, external)
}
/**
* `srcProd` is a relation between a source file and a product: (source, product).
* Note that some source files may not have a product and will not be included in this relation.
*
* `binaryDeps` is a relation between a source file and a binary dependency: (source, binary dependency).
*   This only includes dependencies on classes and jars that do not have a corresponding source/API to track instead.
*   A class or jar with a corresponding source should only be tracked in one of the source dependency relations.
*
* `direct` defines relations for dependencies between internal and external source dependencies.  It includes all types of
*   dependencies, including inheritance.
*
* `publicInherited` defines relations for internal and external source dependencies, only including dependencies
*   introduced by inheritance.
*
* `classes` is a relation between a source file and its generated fully-qualified class names.
*/
private class MRelations(val srcProd: Relation[File, File], val binaryDep: Relation[File, File],
	// direct should include everything in inherited
	val direct: Source, val publicInherited: Source, val classes: Relation[File, String]) extends Relations
{
	def internalSrcDep: Relation[File, File] = direct.internal
	def externalDep: Relation[File, String] = direct.external

	def allSources: collection.Set[File] = srcProd._1s

	def allProducts: collection.Set[File] = srcProd._2s
	def allBinaryDeps: collection.Set[File] = binaryDep._2s
	def allInternalSrcDeps: collection.Set[File] = direct.internal._2s
	def allExternalDeps: collection.Set[String] = direct.external._2s

	def classNames(src: File): Set[String] = classes.forward(src)
	def definesClass(name: String): Set[File] = classes.reverse(name)
	
	def products(src: File): Set[File] = srcProd.forward(src)
	def produced(prod: File): Set[File] = srcProd.reverse(prod)
	
	def binaryDeps(src: File): Set[File] = binaryDep.forward(src)
	def usesBinary(dep: File): Set[File] = binaryDep.reverse(dep)
	
	def internalSrcDeps(src: File): Set[File] = direct.internal.forward(src)
	def usesInternalSrc(dep: File): Set[File] = direct.internal.reverse(dep)

	def externalDeps(src: File): Set[String] = direct.external.forward(src)
	def usesExternal(dep: String): Set[File] = direct.external.reverse(dep)

	def addProduct(src: File, prod: File, name: String): Relations =
		new MRelations( srcProd + (src, prod), binaryDep, direct = direct, publicInherited = publicInherited, classes + (src, name) )

	def addExternalDep(src: File, dependsOn: String, inherited: Boolean): Relations = {
		val newI = if(inherited) publicInherited.addExternal(src, dependsOn) else publicInherited
		val newD = direct.addExternal(src, dependsOn)
		new MRelations( srcProd, binaryDep, direct = newD, publicInherited = newI, classes )
	}

	def addInternalSrcDeps(src: File, dependsOn: Iterable[File], inherited: Iterable[File]): Relations = {
		val newI = publicInherited.addInternal(src, inherited)
		val newD = direct.addInternal(src, dependsOn)
		new MRelations( srcProd, binaryDep, direct = newD, publicInherited = newI, classes )
	}

	def addBinaryDep(src: File, dependsOn: File): Relations =
		new MRelations( srcProd, binaryDep + (src, dependsOn), direct = direct, publicInherited = publicInherited, classes )
	def ++ (o: Relations): Relations =
		new MRelations(srcProd ++ o.srcProd, binaryDep ++ o.binaryDep, direct = direct ++ o.direct, publicInherited = publicInherited ++ o.publicInherited, classes ++ o.classes)
	def -- (sources: Iterable[File]) =
		new MRelations(srcProd -- sources, binaryDep -- sources, direct = direct -- sources, publicInherited = publicInherited -- sources, classes -- sources)

	@deprecated("Broken implementation. OK to remove in 0.14", "0.13.1")
	def groupBy[K](f: File => K): Map[K, Relations] =
	{
		type MapRel[T] = Map[K, Relation[File, T]]
		def outerJoin(srcProdMap: MapRel[File], binaryDepMap: MapRel[File], direct: Map[K, Source], inherited: Map[K, Source],
									classesMap: MapRel[String]): Map[K, Relations] =
		{
			def kRelations(k: K): Relations = {
				def get[T](m: Map[K, Relation[File, T]]) = Relations.getOrEmpty(m, k)
				def getSrc(m: Map[K, Source]): Source = m.getOrElse(k, Relations.emptySource)
				new MRelations( get(srcProdMap), get(binaryDepMap), getSrc(direct), getSrc(inherited), get(classesMap) )
			}
			val keys = (srcProdMap.keySet ++ binaryDepMap.keySet ++ direct.keySet ++ inherited.keySet ++ classesMap.keySet).toList
			Map( keys.map( (k: K) => (k, kRelations(k)) ) : _*)
		}

		def f1[B](item: (File, B)): K = f(item._1)
		outerJoin(srcProd.groupBy(f1), binaryDep.groupBy(f1), direct.groupBySource(f), publicInherited.groupBySource(f), classes.groupBy(f1))
	}

	override def equals(other: Any) = other match {
		case o: MRelations => srcProd == o.srcProd && binaryDep == o.binaryDep && direct == o.direct && publicInherited == o.publicInherited && classes == o.classes
		case _ => false
	}

	override def hashCode = (srcProd :: binaryDep :: direct :: publicInherited :: classes :: Nil).hashCode

  /** Making large Relations a little readable. */
  private val userDir = sys.props("user.dir").stripSuffix("/") + "/"
  private def nocwd(s: String)              = s stripPrefix userDir
  private def line_s(kv: (Any, Any))        = "    " + nocwd("" + kv._1) + " -> " + nocwd("" + kv._2) + "\n"
  private def relation_s(r: Relation[_, _]) = (
    if (r.forwardMap.isEmpty) "Relation [ ]"
    else (r.all.toSeq map line_s sorted) mkString ("Relation [\n", "", "]")
  )
	override def toString = (
	  """
	  |Relations:
	  |  products: %s
	  |  bin deps: %s
	  |  src deps: %s
	  |  ext deps: %s
	  |  class names: %s
	  """.trim.stripMargin.format(List(srcProd, binaryDep, internalSrcDep, externalDep, classes) map relation_s : _*)
	)
}