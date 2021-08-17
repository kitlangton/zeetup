package zymposium.repositories

import zio.blocking.Blocking
import zio.stream.{UStream, ZStream}
import zio.{query => _, _}
import zymposium.QuillContext._
import zymposium.model.{Event, Rsvp}

import java.sql.{Connection, Timestamp, Types}
import java.time.Instant
import java.util.UUID

trait EventRepository {
  def removeRsvp(rsvp: Rsvp): Task[Unit]

  def rsvpStream: UStream[Rsvp]

  def allEvents: Task[List[Event]]
  def allEventsStream: UStream[Event]

  def rsvps(accountId: UUID): Task[List[Rsvp]]
  def createRsvp(rsvp: Rsvp): Task[Unit]

  def save(event: Event): Task[Event]
}

object EventRepository {
  val test: ULayer[Has[EventRepository]]                                              = EventRepositoryTest.layer
  val live: URLayer[Has[Connection] with Has[Blocking.Service], Has[EventRepository]] = EventRepositoryLive.layer

  def save(event: Event): ZIO[Has[EventRepository], Throwable, Event] = ZIO.serviceWith[EventRepository](_.save(event))
}

case class EventRepositoryLive(
    newEventHub: Hub[Event],
    rsvpHub: Hub[Rsvp],
    connection: Connection
) extends EventRepository {

  lazy val env: Has[Connection] = Has(connection)

  implicit val instantEncoder: Encoder[Instant] =
    encoder(Types.TIMESTAMP, (index, value, row) => row.setTimestamp(index, Timestamp.from(value)))

  implicit val instantDecoder: Decoder[Instant] =
    decoder((index, row) => { row.getTimestamp(index).toInstant })

  override def allEvents: Task[List[Event]] =
    run(query[Event]).provide(env)

  override def save(event: Event): Task[Event] =
    for {
      event <- run { query[Event].insert(lift(event)).returningGenerated(_.id) }
        .provide(env)
        .map { uuid => event.copy(id = uuid) }
      _ <- newEventHub.publish(event)
    } yield event

  override def allEventsStream: UStream[Event] =
    ZStream.fromEffect(allEvents.orDie.map(Chunk.fromIterable(_))).flattenChunks ++
      ZStream.fromHub(newEventHub)

  override def createRsvp(rsvp: Rsvp): Task[Unit] =
    run(query[Rsvp].insert(lift(rsvp))).provide(env) *>
      rsvpHub.publish(rsvp).unit

  override def rsvpStream: UStream[Rsvp] =
    ZStream.fromEffect(allRsvps.orDie.map(Chunk.fromIterable)).flattenChunks ++
      ZStream.fromHub(rsvpHub)

  private def allRsvps: Task[List[Rsvp]] =
    run(query[Rsvp]).provide(env)

  override def rsvps(accountId: UUID): Task[List[Rsvp]] =
    run(query[Rsvp].filter(_.accountId == lift(accountId))).provide(env)

  override def removeRsvp(rsvp: Rsvp): Task[Unit] =
    run {
      query[Rsvp].filter(r => r.eventId == lift(rsvp.eventId) && r.accountId == lift(rsvp.accountId)).delete
    }
      .provide(env)
      .unit
}

object EventRepositoryLive {
  val layer: URLayer[Has[Connection], Has[EventRepository]] = {
    for {
      connection  <- ZIO.service[Connection]
      newEventHub <- Hub.bounded[Event](256)
      rsvpHub     <- Hub.bounded[Rsvp](256)
    } yield EventRepositoryLive(newEventHub, rsvpHub, connection)
  }.toLayer
}
