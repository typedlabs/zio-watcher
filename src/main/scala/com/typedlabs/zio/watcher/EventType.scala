package com.typedlabs.zio.watcher

import java.nio.file.{StandardWatchEventKinds, WatchEvent}

/** Type of event raised by `Watcher`. Supports the standard events types as well as arbitrary non-standard types (via `NonStandard`). */
private[watcher] sealed trait EventType
private[watcher] object EventType {
  final case object Created extends EventType
  final case object Deleted extends EventType
  final case object Modified extends EventType
  final case object Overflow extends EventType
  final case class NonStandard(kind: WatchEvent.Kind[_]) extends EventType

  /**
    * Converts ZIO `Watcher.EventType` into a NIO `StandardWatchEventKinds`
    *
    * @param et ZIO event type to convert
    */
  def toWatchEventKind(et: EventType): WatchEvent.Kind[_] = et match {
    case EventType.Created           => StandardWatchEventKinds.ENTRY_CREATE
    case EventType.Modified          => StandardWatchEventKinds.ENTRY_MODIFY
    case EventType.Deleted           => StandardWatchEventKinds.ENTRY_DELETE
    case EventType.Overflow          => StandardWatchEventKinds.OVERFLOW
    case EventType.NonStandard(kind) => kind
  }
}
