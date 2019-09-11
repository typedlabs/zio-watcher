package zio.watcher

import java.nio.file._

import com.sun.nio.file.SensitivityWatchEventModifier
import zio.ZIO
import zio.blocking.Blocking
import zio.stream.Stream

import scala.concurrent.duration._

/**
  * Allows watching the file system for changes to directories and files by using the platform's `WatchService`.
  */
trait Watcher extends Serializable {

  /**
    * Watches specified path for events.
    *
    * @param path file o§r directory to watch for events
    * @param types event types to register for; if `Nil`, all standard event types are registered
    * @param modifiers modifiers to pass to the underlying `WatchService` when registering
    * @param pollTimeout how often should underlying filesystem be pooled for changes
    * @return Stream of events
    */
  def watch(path: Path,
            types: Seq[EventType] = Nil,
            modifiers: Seq[WatchEvent.Modifier] = Seq(SensitivityWatchEventModifier.HIGH),
            pollTimeout: FiniteDuration = 1.second): ZIO[Blocking, Throwable, Stream[Throwable, Event]]

}

object Watcher {

  /**
    * Watches specified path for events.
    *
    * @param path file o§r directory to watch for events
    * @param types event types to register for; if `Nil`, all standard event types are registered
    * @param modifiers modifiers to pass to the underlying `WatchService` when registering
    * @param pollTimeout how often should underlying filesystem be pooled for changes
    * @return Stream of events
    */
  def watch(path: Path,
            types: Seq[EventType] = Seq.empty,
            modifiers: Seq[WatchEvent.Modifier] = Seq.empty,
            pollTimeout: FiniteDuration = 1.second): ZIO[Blocking, Throwable, Stream[Throwable, Event]] = {
    fromFileSystem(FileSystems.getDefault).flatMap(_.watch(path, types, modifiers, pollTimeout))
  }

  /**
    * Creates a [[Watcher]] from a FileSystem
    *
    * @param fs FileSystem to enable the [[Watcher]] on
    * @return
    */
  def fromFileSystem(fs: FileSystem): ZIO[Blocking, Throwable, Watcher] = {
    fromWatchService(fs.newWatchService)
    //ZIO.effect(fs.newWatchService).bracket(ws => UIO.effectTotal(ws.close()))(DefaultWatcher.fromWatchService)
  }

  /**
    * Creates a [[Watcher]] from a WatchService
    *
    * @param ws WatchService to enable the [[Watcher]] on
    * @return
    */
  def fromWatchService(ws: WatchService): ZIO[Blocking, Throwable, Watcher] = {
    //ZIO.effect(ws).bracket(ws => UIO.effectTotal(ws.close()))(DefaultWatcher.fromWatchService)
    DefaultWatcher.fromWatchService(ws)
  }

}
