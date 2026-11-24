package org.bigbluebutton.api.roomstate

import org.bigbluebutton.api.domain.Meeting
import org.bigbluebutton.api.util.UnitSpec

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters._
import scala.xml.XML

class RoomStateRecordingServiceTest extends UnitSpec {

  private val MeetingId = "183f0bf3a0982a127bdb8161e0c44eb696b3e75c-1554230749920"
  private val EarlierId = "6e35e3b2778883f5db637d7a5dba0a427f692e91-1379965122603"
  private val OtherId = "b94d27b9934d3e08a52e52d7da7dabfac484efe3-1379965122604"

  private class Fixture {
    val presentationDir: File = Files.createTempDirectory("room-state-meetings").toFile
    val publishedDir: File = Files.createTempDirectory("room-state-published").toFile

    val service = new RoomStateRecordingService
    service.setPresentationDir(presentationDir.getAbsolutePath)
    service.setPublishedDir(publishedDir.getAbsolutePath)
    service.setServerUrl("https://bbb.example.com")

    def meeting(id: String = MeetingId, record: Boolean = true, allowed: Seq[String] = Seq()): Meeting =
      new Meeting.Builder("ext-" + id.take(6), id, 1554230749920L)
        .withName("Physics & <Maths>")
        .withRecording(record)
        .withMetadata(Map("bbb-origin" -> "Moodle", "not a name" -> "dropped").asJava)
        .withRoomStateRecordIds(allowed.asJava)
        .build()

    // What bbb-export-annotations leaves when a moderator saves the state.
    def saveCurrent(id: String = MeetingId, content: String = "zip"): File = {
      val dir = new File(presentationDir, s"$id/$id/room-state/current")
      dir.mkdirs()
      val archive = new File(dir, "room-state.zip")
      Files.write(archive.toPath, content.getBytes)
      archive
    }

    def published(id: String): File = new File(publishedDir, s"state/$id")
  }

  "A meeting that ends" should "publish its saved state as a recording in the state format" in new Fixture {
    saveCurrent(content = "saved state")
    service.publish(meeting()) shouldBe true

    new String(Files.readAllBytes(new File(published(MeetingId), "room-state.zip").toPath)) shouldBe "saved state"

    val metadata = XML.loadFile(new File(published(MeetingId), "metadata.xml"))
    (metadata \ "id").text shouldBe MeetingId
    (metadata \ "state").text shouldBe "published"
    (metadata \ "published").text shouldBe "true"
    (metadata \ "start_time").text shouldBe "1554230749920"
    (metadata \ "meeting" \ "@name").text shouldBe "Physics & <Maths>"
    (metadata \ "meeting" \ "@externalId").text shouldBe "ext-183f0b"
    (metadata \ "meta" \ "bbb-origin").text shouldBe "Moodle"
    (metadata \ "meta" \ "meetingName").text shouldBe "Physics & <Maths>"
    (metadata \ "meta").head.child.map(_.label) should not contain "not a name"
    (metadata \ "playback" \ "format").text shouldBe "state"
    (metadata \ "playback" \ "link").text shouldBe s"https://bbb.example.com/bigbluebutton/api/getRoomState?recordID=$MeetingId"
    (metadata \ "playback" \ "size").text shouldBe "11"
    publishedDir.listFiles().flatMap(_.listFiles()).map(_.getName) shouldBe Array(MeetingId)
  }

  it should "publish nothing when no state was saved, or when it is not a recorded meeting" in new Fixture {
    service.publish(meeting()) shouldBe false

    saveCurrent()
    service.publish(meeting(record = false)) shouldBe false
    published(MeetingId).exists shouldBe false
  }

  "The states a meeting can load" should "be the one saved in it and the published ones it was created with" in new Fixture {
    saveCurrent(EarlierId)
    service.publish(meeting(EarlierId))
    saveCurrent(OtherId)
    service.publish(meeting(OtherId))

    val running = meeting(allowed = Seq(EarlierId, "c94d27b9934d3e08a52e52d7da7dabfac484efe3-1379965122605"))
    service.list(running).asScala.map(_.id) shouldBe Seq(EarlierId)
    service.list(running).get(0).name shouldBe "Physics & <Maths>"
    service.list(running).get(0).savedAt should be > 0L

    saveCurrent()
    service.list(running).asScala.map(_.id) shouldBe Seq("current", EarlierId)

    service.archiveOf(running, "current") shouldBe saveCurrent()
    service.archiveOf(running, EarlierId) shouldBe new File(published(EarlierId), "room-state.zip")
  }

  it should "not include a state the meeting was not created with, nor one that is not published" in new Fixture {
    saveCurrent(EarlierId)
    service.publish(meeting(EarlierId))
    saveCurrent(OtherId)
    service.publish(meeting(OtherId))

    val running = meeting(allowed = Seq(EarlierId))
    service.archiveOf(running, OtherId) shouldBe null
    service.archiveOf(running, "current") shouldBe null
    service.archiveOf(running, null) shouldBe null
    service.archiveOf(meeting(allowed = Seq("../../etc")), "../../etc") shouldBe null

    // Unpublishing or deleting the recording moves its folder out of the published directory.
    org.apache.commons.io.FileUtils.deleteDirectory(published(EarlierId))
    service.list(running).asScala shouldBe empty
    service.archiveOf(running, EarlierId) shouldBe null
  }

  "The published state of a recording" should "only be found for a well-formed record id" in new Fixture {
    saveCurrent()
    service.publish(meeting())

    service.publishedArchive(MeetingId) should not be null
    service.publishedArchive(OtherId) shouldBe null
    service.publishedArchive(s"../state/$MeetingId") shouldBe null
    service.publishedArchive(null) shouldBe null
  }
}
