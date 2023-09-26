package io.github.memo33
package sc4pac
package cli

import scala.collection.immutable as I
import caseapp.{RemainingArgs, ArgsName, HelpMessage, ExtraName, ValueDescription, Group, Tag}
import zio.{ZIO, Task}

import sc4pac.error.Sc4pacNotInteractive
import sc4pac.Resolution.BareModule
import sc4pac.JsonData as JD

// see https://github.com/coursier/coursier/blob/main/modules/cli/src/main/scala/coursier/cli/Coursier.scala
// and related files

trait Command[A] extends caseapp.Command[A] {

  def commandName: String = names.headOption.map(parts => (BuildInfo.name :: parts).mkString(" ")).getOrElse(BuildInfo.name + " <COMMAND>")

  override def helpFormat = super.helpFormat.copy(sortGroups = Commands.sortHelpLast)
}

object Commands {

  sealed abstract class Sc4pacCommandOptions extends Product with Serializable

  // TODO strip escape sequences if jansi failed with a link error
  private[sc4pac] def gray(msg: String): String = s"${27.toChar}[90m" + msg + Console.RESET  // aka bright black
  private[sc4pac] def emph(msg: String): String = Console.BOLD + msg + Console.RESET

  private[sc4pac] val sortHelpLast: Option[Seq[String] => Seq[String]] =
    Some(groups => groups.partition(_ == "Help") match { case (help, nonHelp) => nonHelp ++ help })

  private def runMainExit(task: Task[Unit], exit: Int => Nothing): Nothing = {
    unsafeRun(task.fold(
      failure = {
        case abort: sc4pac.error.Sc4pacAbort => { System.err.println(Array("Operation aborted.", abort.msg).mkString(" ")); exit(1) }
        case abort: sc4pac.error.Sc4pacTimeout => { System.err.println(Array("Operation aborted.", abort.getMessage).mkString(" ")); exit(1) }
        case abort: sc4pac.error.Sc4pacNotInteractive => { System.err.println(s"Operation aborted as terminal is non-interactive: ${abort.getMessage}"); exit(1) }
        case abort: sc4pac.error.Sc4pacVersionNotFound => { System.err.println(s"Operation aborted. ${abort.getMessage}"); exit(1) }
        case e => { e.printStackTrace(); exit(1) }
      },
      success = _ => exit(0)
    ))
  }

  @ArgsName("packages...")
  @HelpMessage(s"""
    |Add new packages to install explicitly.
    |
    |Afterwards, run ${emph("sc4pac update")} for the changes to take effect.
    |
    |Example:
    |  sc4pac add memo:essential-fixes   ${gray("# packages of the form <group>:<package-name>")}
    """.stripMargin.trim)
  final case class AddOptions(
    // @Group("foo")
    // @Tag("foo")
    // @ExtraName("V")
    // @ValueDescription("stages")
    // @HelpMessage("Set a value")
    // aa: String
  ) extends Sc4pacCommandOptions

  case object Add extends Command[AddOptions] {
    def run(options: AddOptions, args: RemainingArgs): Unit = {
      if (args.all.isEmpty) {
        fullHelpAsked(commandName)
      }
      val task: Task[Unit] = for {
        mods   <- ZIO.fromEither(Sc4pac.parseModules(args.all)).catchAll { (err: ErrStr) =>
                    error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                  }
        config <- JD.Plugins.readOrInit.map(_.config)
        pac    <- Sc4pac.init(config)
        _      <- pac.add(mods)
      } yield ()
      runMainExit(task, exit)
    }
  }

  @HelpMessage(s"""
    |Update all installed packages to their latest version and install any missing packages.
    |
    |In particular, this installs the explicitly added packages and, implicitly, all their dependencies.
    """.stripMargin.trim)
  final case class UpdateOptions() extends Sc4pacCommandOptions

  case object Update extends Command[UpdateOptions] {
    def run(options: UpdateOptions, args: RemainingArgs): Unit = {
      val task = for {
        pluginsData  <- JD.Plugins.readOrInit
        pac          <- Sc4pac.init(pluginsData.config)
        flag         <- pac.update(pluginsData.explicit, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsData.pluginsRootAbs)
      } yield ()
      runMainExit(task, exit)
    }
  }

  @ArgsName("packages...")
  @HelpMessage(s"""
    |Remove packages that have been installed explicitly.
    |
    |Afterwards, run ${emph("sc4pac update")} for the changes to take effect.
    |
    |Examples:
    |  sc4pac remove --interactive          ${gray("# Interactively select packages to remove.")}
    |  sc4pac remove memo:essential-fixes   ${gray("# Remove package <group>:<package-name>.")}
    |""".stripMargin.trim)
  final case class RemoveOptions(
    @ExtraName("i") @HelpMessage("Interactively select packages to remove") @Group("Main") @Tag("Main")
    interactive: Boolean = false
  ) extends Sc4pacCommandOptions

  case object Remove extends Command[RemoveOptions] {
    def run(options: RemoveOptions, args: RemainingArgs): Unit = {
      if (!options.interactive && args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task: Task[Unit] = for {
          mods   <- ZIO.fromEither(Sc4pac.parseModules(args.all)).catchAll { (err: ErrStr) =>
                      error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                    }
          config <- JD.Plugins.readOrInit.map(_.config)
          pac    <- Sc4pac.init(config)
          _      <- if (options.interactive) {
                      Prompt.ifInteractive(
                        onTrue = pac.removeSelect(),
                        onFalse = ZIO.fail(new Sc4pacNotInteractive(s"Pass packages to remove as arguments, non-interactively.")))
                    } else {
                      pac.remove(mods)
                    }
        } yield ()
        runMainExit(task, exit)
      }
    }
  }

  @ArgsName("search text...")
  @HelpMessage(s"""
    |Search for the name of a package.
    |The results are ordered such that the best match is displayed at the bottom.
    |
    |Examples:
    |
    |  sc4pac search "Pause border"
    |  ${gray(">>>")} (1) smp:yellow-pause-thingy-remover
    |  ${gray(">>>")}         Remove the yellow border from the UI when the game is paused
    |
    |  sc4pac search --threshold 20 "Pause border"    ${gray("# Decrease threshold for more results.")}
    |  ${gray(">>>")} ...
    |
    """.stripMargin.trim)
  final case class SearchOptions(
    @ValueDescription("number") @Group("Search") @Tag("Search")
    @HelpMessage(s"Fuziness (0..100, default=${Constants.fuzzySearchThreshold}): Smaller numbers lead to more results.")
    threshold: Int = Constants.fuzzySearchThreshold  // 0..100, default 50
  ) extends Sc4pacCommandOptions

  case object Search extends Command[SearchOptions] {
    def run(options: SearchOptions, args: RemainingArgs): Unit = {
      if (args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task: Task[Unit] = for {
          pluginsData  <- JD.Plugins.readOrInit
          pac          <- Sc4pac.init(pluginsData.config)
          query        =  args.all.mkString(" ")
          searchResult <- pac.search(query, options.threshold)
          installed    <- JD.PluginsLock.listInstalled.map(_.map(_.toBareDep).toSet)
        } yield {
          if (searchResult.isEmpty) {
            error(caseapp.core.Error.Other("No packages found. Try to lower the `--threshold` parameter."))
          } else {
            for (((mod, ratio, description), idx) <- searchResult.zipWithIndex.reverse) {
              pac.logger.logSearchResult(idx, mod, description, installed(mod))
            }
          }
        }
        runMainExit(task, exit)
      }
    }
  }

  @ArgsName("packages")
  @HelpMessage(s"""
    |Display more information about a package.
    |
    |Examples:
    |  sc4pac info memo:essential-fixes
    """.stripMargin.trim)
  final case class InfoOptions() extends Sc4pacCommandOptions

  case object Info extends Command[InfoOptions] {
    def run(options: InfoOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Nil => fullHelpAsked(commandName)
        case pkgNames =>
          val task: Task[Unit] = for {
            mods         <- ZIO.fromEither(Sc4pac.parseModules(pkgNames)).catchAll { (err: ErrStr) =>
                              error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                            }
            pluginsData  <- JD.Plugins.readOrInit
            pac          <- Sc4pac.init(pluginsData.config)
            infoResults  <- ZIO.foreachPar(mods)(pac.info)
          } yield {
            val (found, notFound) = infoResults.zip(mods).partition(_._1.isDefined)
            if (notFound.nonEmpty) {
              error(caseapp.core.Error.Other("Package not found: " + notFound.map(_._2.orgName).mkString(" ")))
            } else {
              for ((infoResultOpt, idx) <- found.zipWithIndex) {
                if (idx > 0) pac.logger.log("")
                pac.logger.logInfoResult(infoResultOpt._1.get)
              }
            }
          }
          runMainExit(task, exit)
      }
    }
  }

  @HelpMessage("List all installed packages.")
  final case class ListOptions() extends Sc4pacCommandOptions

  case object List extends Command[ListOptions] {
    def run(options: ListOptions, args: RemainingArgs): Unit = {
      val task: Task[Unit] = for {
        pluginsData  <- JD.Plugins.readOrInit
        pac          <- Sc4pac.init(pluginsData.config)  // only used for logging
        installed    <- JD.PluginsLock.listInstalled
      } yield {
        val sorted = installed.sortBy(mod => (mod.group.value, mod.name.value))
        val explicit: Set[BareModule] = pluginsData.explicit.toSet
        for (mod <- sorted) {
          pac.logger.logInstalled(mod, explicit(mod.toBareDep))
        }
      }
      runMainExit(task, exit)
    }
  }

  @ArgsName("variants...")
  @HelpMessage(s"""
    |Select variants to reset in order to choose a different package variant.
    |
    |For some packages you install, you can choose from a list of package variants that match your preferences. Your choices are stored in a configuration file.
    |
    |After resetting a variant identifier, the next time you run ${emph("sc4pac update")}, you will be asked to choose a new variant.
    |
    |Examples:
    |  sc4pac variant reset --interactive    ${gray("# Interactively select variants to reset.")}
    |  sc4pac variant reset "driveside"      ${gray("# Reset the \"driveside\" variant.")}
    """.stripMargin.trim)
  final case class VariantResetOptions(
    @ExtraName("i") @HelpMessage("Interactively select variants to reset") @Group("Main") @Tag("Main")
    interactive: Boolean = false
  ) extends Sc4pacCommandOptions

  case object VariantReset extends Command[VariantResetOptions] {
    override def names = I.List(I.List("variant", "reset"))
    def run(options: VariantResetOptions, args: RemainingArgs): Unit = {
      if (!options.interactive && args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task = JD.Plugins.readOrInit.flatMap { data =>
          if (data.config.variant.isEmpty) {
            ZIO.succeed(println("The list of configured variants is empty. The next time you install a package that comes in variants, you can choose again."))
          } else {
            val variants: Seq[(String, String)] = data.config.variant.toSeq.sorted
            val select: Task[Seq[String]] =
              if (!options.interactive) {
                ZIO.succeed(args.all)
              } else {
                Prompt.ifInteractive(
                  onTrue = Prompt.numberedMultiSelect("Select variants to reset:", variants, (k, v) => s"$k = $v").map(_.map(_._1)),
                  onFalse = ZIO.fail(new Sc4pacNotInteractive(s"Pass variants to remove as arguments, non-interactively."))
                )
              }
            for {
              selected <- select
              data2    =  data.copy(config = data.config.copy(variant = data.config.variant -- selected))
              _        <- JsonIo.write(JD.Plugins.path, data2, None)(ZIO.succeed(()))
            } yield ()
          }
        }
        runMainExit(task, exit)
      }
    }
  }

  @ArgsName("channel-URL")
  @HelpMessage(s"""
    |Add a channel to fetch package metadata from.
    |
    |Examples:
    |  sc4pac channel add "${Constants.defaultChannelUrls.head}"
    |  sc4pac channel add "file://absolute/local/path/to/channel/json/"
    """.stripMargin.trim)
  final case class ChannelAddOptions() extends Sc4pacCommandOptions

  case object ChannelAdd extends Command[ChannelAddOptions] {
    override def names = I.List(I.List("channel", "add"))
    def run(options: ChannelAddOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Seq[String](text) =>
          MetadataRepository.parseChannelUrl(text) match {
            case Left(err) => error(caseapp.core.Error.Other(s"Malformed URL: $err"))
            case Right(uri) =>
              val task: Task[Unit] = for {
                data  <- JD.Plugins.readOrInit
                data2 =  data.copy(config = data.config.copy(channels = (data.config.channels :+ uri).distinct))
                _     <- JsonIo.write(JD.Plugins.path, data2, None)(ZIO.succeed(()))
              } yield ()
              runMainExit(task, exit)
          }
        case Nil => fullHelpAsked(commandName)
        case _ => error(caseapp.core.Error.Other("A single argument is needed: channel-URL"))
      }
    }
  }

  @ArgsName("URL-patterns")
  @HelpMessage(s"""
    |Select channels to remove.
    |
    |Examples:
    |  sc4pac channel remove --interactive     ${gray("# Interactively select channels to remove.")}
    |  sc4pac channel remove "github.com"      ${gray("# Remove channel URLs containing \"github.com\".")}
    """.stripMargin.trim)
  final case class ChannelRemoveOptions(
    @ExtraName("i") @HelpMessage("Interactively select channels to remove") @Group("Main") @Tag("Main")
    interactive: Boolean = false
  ) extends Sc4pacCommandOptions

  case object ChannelRemove extends Command[ChannelRemoveOptions] {
    override def names = I.List(I.List("channel", "remove"))
    def run(options: ChannelRemoveOptions, args: RemainingArgs): Unit = {
      if (!options.interactive && args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task = JD.Plugins.readOrInit.flatMap { data =>
          if (data.config.channels.isEmpty) {
            ZIO.succeed(println("The list of channel URLs is already empty."))
          } else {
            for {
              isSelected   <- if (options.interactive) {
                                Prompt.ifInteractive(
                                  onTrue = Prompt.numberedMultiSelect("Select channels to remove:", data.config.channels).map(_.toSet),
                                  onFalse = ZIO.fail(new Sc4pacNotInteractive(s"Pass channel URL patterns as arguments, non-interactively."))
                                )
                              } else {
                                ZIO.succeed((url: java.net.URI) => args.all.exists(pattern => url.toString.contains(pattern)))
                              }
              (drop, keep) =  data.config.channels.partition(isSelected)
              _            <- ZIO.succeed { if (drop.nonEmpty) println(("The following channels have been removed:" +: drop).mkString(f"%n")) }
              data2        =  data.copy(config = data.config.copy(channels = keep))
              _            <- JsonIo.write(JD.Plugins.path, data2, None)(ZIO.succeed(()))
            } yield ()
          }
        }
        runMainExit(task, exit)
      }
    }
  }


  @HelpMessage(f"List the channel URLs.%nThe first channel has the highest priority when resolving dependencies.")
  final case class ChannelListOptions() extends Sc4pacCommandOptions

  case object ChannelList extends Command[ChannelListOptions] {
    override def names = I.List(I.List("channel", "list"))
    def run(options: ChannelListOptions, args: RemainingArgs): Unit = {
      val task = for {
        pluginsData <- JD.Plugins.readOrInit
      } yield {
        for (url <- pluginsData.config.channels) {
          println(url)
        }
      }
      runMainExit(task, exit)
    }
  }

  @ArgsName("YAML-input-directories...")
  @HelpMessage("""
    |Build a channel locally by converting YAML files to JSON.
    |
    |Examples:
    |  sc4pac channel build --output "channel/json/" "channel/yaml/"
    """.stripMargin.trim)
  final case class ChannelBuildOptions(
    @ExtraName("o") @ValueDescription("dir") @HelpMessage("Output directory for JSON files") @Group("Main") @Tag("Main")
    output: String
  ) extends Sc4pacCommandOptions

  /** For internal use, convert yaml files to json.
    * Usage: `./sc4pac build-channel ./channel-testing/`
    */
  case object ChannelBuild extends Command[ChannelBuildOptions] {
    override def names = I.List(I.List("channel", "build"))
    def run(options: ChannelBuildOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Nil => error(caseapp.core.Error.Other("An argument is needed: YAML input directory"))
        case inputs => ChannelUtil.convertYamlToJson(inputs.map(os.Path(_, os.pwd)), os.Path(options.output, os.pwd))
      }
    }
  }

}

object CliMain extends caseapp.core.app.CommandsEntryPoint {
  import Commands.gray

  val commands = Seq(
    Commands.Add,
    Commands.Update,
    Commands.Remove,
    Commands.Search,
    Commands.Info,
    Commands.List,
    Commands.VariantReset,
    Commands.ChannelAdd,
    Commands.ChannelRemove,
    Commands.ChannelList,
    Commands.ChannelBuild)

  val progName = BuildInfo.name

  override val description = s"""
    |A package manager for SimCity 4 plugins (Version ${BuildInfo.version}).
    |
    |Examples:
    |
    |  sc4pac add memo:essential-fixes    ${gray("# Add new package to install.")}
    |  sc4pac update                      ${gray("# Download and install everything as needed.")}
    |  sc4pac add --help                  ${gray("# Display more information about a command.")}
    |
    """.stripMargin.trim

  override def main(args: Array[String]): Unit = {
    if (args.length == 1 && (args(0) == "--version" || args(0) == "-v")) {
      println(BuildInfo.version)
    } else {
      try {
        // First of all, we install ansi-aware streams, so that colors are
        // interpreted correctly on Windows (for example for the help text).
        org.fusesource.jansi.AnsiConsole.systemInstall()  // this alters System.out and System.err
        if (Constants.noColor) {
          org.fusesource.jansi.AnsiConsole.out().setMode(org.fusesource.jansi.AnsiMode.Strip)
          org.fusesource.jansi.AnsiConsole.err().setMode(org.fusesource.jansi.AnsiMode.Strip)
        }
      } catch {
        case e: java.lang.UnsatisfiedLinkError =>  // in case something goes really wrong and no suitable jansi native library is included
      }
      super.main(args)
    }
  }
}
