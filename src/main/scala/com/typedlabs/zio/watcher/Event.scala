package zio.watcher

import java.nio.file.{Path, StandardWatchEventKinds, WatchEvent}

/** Event raised by `Watcher`. Supports standard events as well as arbitrary non-standard events (via `NonStandard`). */
sealed abstract class Event
object Event {
  final case class Created(path: Path, count: Int) extends Event
  final case class Deleted(path: Path, count: Int) extends Event
  final case class Modified(path: Path, count: Int) extends Event
  final case class Overflow(count: Int) extends Event
  final case class NonStandard(event: WatchEvent[_], registeredDirectory: Path) extends Event

  /**
    * Converts a NIO `WatchEvent` to an ZIO `Watcher.Event`.
    *
    * @param e event to convert
    * @param registeredDirectory path of the directory for which the event's path is relative
    */
  def fromWatchEvent(e: WatchEvent[_], registeredDirectory: Path): Event =
    e match {
      case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_CREATE =>
        Event.Created(registeredDirectory.resolve(e.context), e.count)
      case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_MODIFY =>
        Event.Modified(registeredDirectory.resolve(e.context), e.count)
      case e: WatchEvent[Path] @unchecked if e.kind == StandardWatchEventKinds.ENTRY_DELETE =>
        Event.Deleted(registeredDirectory.resolve(e.context), e.count)
      case e if e.kind == StandardWatchEventKinds.OVERFLOW =>
        Event.Overflow(e.count)
      case e => Event.NonStandard(e, registeredDirectory)
    }

  /** Determines the path for which the supplied event references. */
  def pathOf(event: Event): Option[Path] = event match {
    case Event.Created(p, _)  => Some(p)
    case Event.Deleted(p, _)  => Some(p)
    case Event.Modified(p, _) => Some(p)
    case Event.Overflow(_)    => None
    case Event.NonStandard(e, registeredDirectory) =>
      if (e.context.isInstanceOf[Path])
        Some(registeredDirectory.resolve(e.context.asInstanceOf[Path]))
      else None
  }
}
