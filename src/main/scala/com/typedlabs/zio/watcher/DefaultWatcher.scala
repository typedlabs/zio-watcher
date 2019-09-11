package zio.watcher

import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

import log.effect.LogWriter
import log.effect.zio.ZioLogWriter
import org.log4s._
import zio.blocking.Blocking
import zio.stream.Stream
import zio.watcher.DefaultWatcher.Registration
import zio.{UIO, _}

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

private[watcher] class DefaultWatcher(ws: WatchService, registrations: Ref[Map[WatchKey, Registration]], logger: LogWriter[Task])
    extends Watcher {

  private[this] val defaultEventTypes =
    List(EventType.Created, EventType.Deleted, EventType.Modified, EventType.Overflow)

  override def watch(path: Path,
                     types: Seq[EventType],
                     modifiers: Seq[WatchEvent.Modifier],
                     pollTimeout: FiniteDuration): ZIO[Blocking, Throwable, Stream[Throwable, Event]] = {
    isDir(path).flatMap { dir =>
      if (dir) watchDirectory(path, types, modifiers)
      else watchFile(path, types, modifiers)
    } *> Task.effect(events(pollTimeout))
  }

  private def isDir(p: Path): ZIO[Blocking, Throwable, Boolean] =
    ZIO.accessM { blocking =>
      blocking.blocking.effectBlocking(Files.isDirectory(p))
    }

  private def track(key: WatchKey, r: DefaultWatcher.Registration): UIO[Unit] = {
    registrations.update(x => x.updated(key, r)).unit
  }

  private def registerUnTracked(path: Path,
                                types: Seq[EventType],
                                modifiers: Seq[WatchEvent.Modifier]): ZIO[Blocking, Throwable, WatchKey] =
    ZIO.accessM { blocking =>
      blocking.blocking.effectBlocking {
        val typesWithDefaults =
          if (types.isEmpty) defaultEventTypes
          else types
        val kinds = typesWithDefaults.map(EventType.toWatchEventKind)
        path.register(ws, kinds.toArray, modifiers: _*)
      }
    }

  private def cancelWatch(key: WatchKey) = {
    UIO.effectTotal(key.cancel())
  }

  private def watchDirectory(path: Path, types: Seq[EventType], modifiers: Seq[WatchEvent.Modifier]): ZIO[Blocking, Throwable, Unit] =
    ZIO.accessM { blocking =>
      val (supplementedTypes, suppressCreated) =
        if (types.isEmpty) (defaultEventTypes, false)
        else if (types.contains(EventType.Created)) (types, false)
        else (EventType.Created +: types, true)

      val dirs: Task[List[Path]] =
        blocking.blocking.effectBlocking {
          var dirs: List[Path] = Nil
          Files.walkFileTree(
            path,
            new SimpleFileVisitor[Path] {
              override def preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
                dirs = path :: dirs
                FileVisitResult.CONTINUE
              }
            }
          )
          dirs
        }

      val r = dirs.flatMap { paths =>
        ZIO.foreach(paths) { path =>
          registerUnTracked(path, supplementedTypes, modifiers)
            .flatMap { key =>
              track(
                key,
                Registration(
                  path,
                  supplementedTypes,
                  modifiers,
                  _ => true,
                  recurse = true,
                  suppressCreated = suppressCreated,
                  cleanup = cancelWatch(key)
                )
              )

            }
        }

      }

      r.unit
    }

  private def watchFile(path: Path, types: Seq[EventType], modifiers: Seq[WatchEvent.Modifier]): ZIO[Blocking, Throwable, Unit] = {
    val registered = registerUnTracked(path.getParent, types, modifiers).flatMap(
      key =>
        track(
          key,
          Registration(
            path,
            types,
            modifiers,
            e => Event.pathOf(e).forall(ep => path == ep),
            recurse = false,
            suppressCreated = false,
            cleanup = UIO.effectTotal(key.cancel())
          )
      ))
    registered
  }

  private def cancelRegistrations: UIO[Unit] = {
    val result = for {
      _ <- logger.debug("Cancelling up registrations.")
      current <- registrations.get
    } yield {
      val r = current.map {
        case (key, reg) =>
          for {
            res <- registrations
              .modify[UIO[Unit]] { s =>
                s.get(key).map(_.cleanup).getOrElse(UIO.unit) -> (s - key)
              }
              .flatten
            _ <- logger.info(s"Watch on ${reg.path} was cancelled")
          } yield res
      }

      ZIO.foreach(r)(identity).unit
    }

    result.flatten
      .catchAll { _: Throwable =>
        // TODO: improve this since logging will has a task
        // logger.error("Error terminating watches ", err)
        UIO.unit
      }

  }

  /**
    * Stream of events for paths that have been registered or watched.
    *
    * @param pollTimeout amount of time for which the underlying platform is polled for events
    */
  private def events(pollTimeout: FiniteDuration): Stream[Throwable, Event] = {
    val poll: Task[Option[(WatchKey, List[Event])]] = Task.effect {
      val key = ws.poll(pollTimeout.toMillis, TimeUnit.MILLISECONDS)
      if (key eq null) None
      else {
        val events = key.pollEvents.asScala.toList
        key.reset
        val keyPath = key.watchable.asInstanceOf[Path]
        Some(key -> events.map(evt => Event.fromWatchEvent(evt, keyPath)))
      }
    }

    val eventsStream =
      Stream
        .repeatEffect {
          poll
        }
        .collect { case Some(tup) => tup }
        .zip(Stream.fromEffect(registrations.get))
        .flatMap {
          case ((key, events), activeRegistrations) =>
            val reg: Option[Registration] = activeRegistrations.get(key)

            val filteredEvents = reg
              .map(reg =>
                events.filter(e =>
                  reg.eventPredicate(e) && !(e
                    .isInstanceOf[Event.Created] && reg.suppressCreated)))
              .getOrElse(Nil)

            Stream.fromIterable(filteredEvents)
        }
        .forever

    eventsStream.ensuring(cancelRegistrations)
  }

}

private[watcher] object DefaultWatcher {

  /** Represents a path under watch
    *
    * @param path path to be watched
    * @param types events to be wathed on
    * @param modifiers watch service modifers from [[java.nio.file.WatchEvent.Modifier]]
    * @param eventPredicate predicate to skip un-relevant watch events such as [[EventType.Overflow]]
    * @param recurse weather watch service should also watch children, true for directories, false for files
    * @param suppressCreated weather [[EventType.Created]] should be watched
    * @param cleanup task that allows to cancel a watch
    */
  final case class Registration(path: Path,
                                types: Seq[EventType],
                                modifiers: Seq[WatchEvent.Modifier],
                                eventPredicate: Event => Boolean,
                                recurse: Boolean,
                                suppressCreated: Boolean,
                                cleanup: UIO[Unit])

  def fromWatchService(ws: WatchService): ZIO[Blocking, Exception, Watcher] = {
    for {
      registrations <- Ref.make(Map.empty[WatchKey, Registration])
      logger <- ZioLogWriter.log4sFromLogger.provide(getLogger(classOf[DefaultWatcher]))
    } yield new DefaultWatcher(ws, registrations, logger)
  }

}
