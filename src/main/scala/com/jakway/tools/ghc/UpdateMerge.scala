package com.jakway.tools.ghc

import java.io.{File, InputStream, OutputStream, PrintWriter}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

import scala.sys.process.Process
import scala.sys.process.ProcessBuilder
import scala.sys.process.{Process, ProcessIO}
import scala.util.Try

object ProcessUtils {
  val enc = "UTF-8"

  def runGetStreams(pb: ProcessBuilder): (String, String, Int) = {
    val stdoutSb = new StringBuilder()
    val stderrSb = new StringBuilder()

    def writeOut(sb: StringBuilder)(stream: InputStream): Unit = {
      val source = scala.io.Source.fromInputStream(stream, enc)
      source.getLines().foreach(s => sb.append(s + System.lineSeparator()))
    }

    def writeStdout = writeOut(stdoutSb) _
    def writeStderr = writeOut(stderrSb) _

    val io = new ProcessIO(_ => (), writeStdout, writeStderr)

    val exitValue = pb.run(io).exitValue()

    (stdoutSb.toString(), stderrSb.toString(), exitValue)
  }

  def runRedirectOnError(pb: ProcessBuilder,
                         stdoutFile: File,
                         stderrFile: File): Int = {

    val (stdout, stderr, exitValue) = runGetStreams(pb)

    if(exitValue != 0) {
      val stdoutWriter = new PrintWriter(stdoutFile)
      stdoutWriter.write(stdout.toString)
      Try { stdoutWriter.close() }

      val stderrWriter = new PrintWriter(stderrFile)
      stderrWriter.write(stderr.toString)
      Try { stderrWriter.close() }
    }

    exitValue
  }
}

object UpdateMerge {
  val usage: String = "scala UpdateMerge [ghc directories...]"

  def main(args: Array[String]): Unit = {
    if(args.isEmpty) {
      println(usage)
    } else {
      val dirs = args.map(new File(_))

      //check all dirs BEFORE running anything
      dirs.foreach(checkCwd)

      dirs.foreach(updateMerge)
    }
  }

  def updateMerge(dir: File): Unit = {
    checkCwd(dir)


    lazy val buildOrRebuild = make(dir) #|| rebuild(dir)

    val exitCode = (Git.pullAndMergeMasterWithCurrentBranch(dir) #&& buildOrRebuild).!

    println(s"Finished with $dir, final exit code: $exitCode")
  }

  def checkCwd(cwd: File): Unit = {
    case class CheckCwdException(val msg: String)
      extends RuntimeException(msg)

    def err(msg: String): Unit = 
      throw CheckCwdException(s"Error with cwd=$cwd: " + msg)

    if(cwd == null) {
      err("is null")
    } else if(!cwd.exists()) {
      err("does not exist")
    } else if(!cwd.isDirectory()) {
      err("is not a directory")
    }
  }



  def make(cwd: File): ProcessBuilder = {
    checkCwd(cwd)

    Process(Seq("make"), Some(cwd)) 
  }

  def rebuild(cwd: File): ProcessBuilder = {
    checkCwd(cwd)

    //clean thoroughly
    val clean = Seq(Process(Seq("make", "clean"), Some(cwd)), 
      //using this instead of findObjectFiles to prevent side effects
      Process("""find . \( -type f -name '*.hi' -or -name '*.o' \) -delete""", Some(cwd)),
      Process(Seq("make", "distclean"), Some(cwd)),
      Process(Seq("git", "clean", "-f", "-d"), Some(cwd)),
      Git.updateSubmodules(cwd),
      Process(Seq("git", "submodule", "foreach", "git", "reset", "--hard"), Some(cwd)))
      .reduceLeft(_ ### _)


    val build: ProcessBuilder = Seq(Process("./boot", Some(cwd)),
      Process("./configure", Some(cwd)),
      make(cwd))
    .reduceLeft(_ #&& _)

    
    clean ### build
  }

  def findObjectFiles(dir: File): Set[File] = {
    checkCwd(dir)

    class Visitor extends SimpleFileVisitor[Path] {
      var files: Set[File] = Set()
      override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
        if(!path.toFile.isDirectory && (path.endsWith(".hi") || path.endsWith(".o"))) {
          files = files + path.toFile
        }

        FileVisitResult.CONTINUE
      }
    }

    val visitor = new Visitor()
    Files.walkFileTree(new File(".").toPath, visitor)

    visitor.files
  }
}


object Git {
  private class GitException(val msg: String)
    extends RuntimeException(msg)

  private case class RevParseException(override val msg: String)
    extends GitException(msg)

  private def checkout(cwd: File)(branch: String): ProcessBuilder = {
    Process(Seq("git","checkout",branch), Some(cwd))
  }

  private def pull(cwd: File): ProcessBuilder = {
    //pull without opening $EDITOR
    //see https://stackoverflow.com/questions/11744081/why-is-git-prompting-me-for-a-post-pull-merge-commit-message
    Process(Seq("git", "pull", "--no-edit"), Some(cwd))
  }

  private def mergeMaster(cwd: File): ProcessBuilder = {
    Process(Seq("git", "merge", "master", "--no-edit"), Some(cwd))
  }

  private def getCurrentBranch(cwd: File): ProcessBuilder = {
    Process(Seq("git", "rev-parse", "--abbrev-ref", "HEAD"), Some(cwd))
  }

  def updateSubmodules(cwd: File): ProcessBuilder = {
    Process(Seq("git", "submodule", "--init", "--recursive"), Some(cwd)),
  }

  private def readCurrentBranch(cwd: File): String = {
    val pb = getCurrentBranch(cwd)

    val (stdout, stderr, exitValue) = ProcessUtils.runGetStreams(pb)

    val genericErrorMessage: String = s"Unexpected results from git rev-parse " +
      s"(ProcessBuilder < $pb >), stdout=$stdout, stderr=$stderr," +
      s" exitValue=$exitValue"

    if(exitValue != 0) {
      throw RevParseException(genericErrorMessage)
    } else if(stdout.lines.length != 1) {
      throw RevParseException(genericErrorMessage +
        s": expected stdout.lines.length == 1, but got ${stdout.lines.length}")
    } else {
      stdout.trim
    }
  }

  def pullAndMergeMasterWithCurrentBranch(cwd: File): ProcessBuilder = {
    val currentBranch = readCurrentBranch(cwd)

    checkout(cwd)("master") #&&
      pull(cwd) #&&
      checkout(cwd)(currentBranch) #&&
      mergeMaster(cwd) #&&
      updateSubmodules(cwd)
  }
}
