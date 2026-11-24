package org.bigbluebutton.api.roomstate

import com.google.gson.{ JsonObject, JsonParser }
import org.bigbluebutton.api.util.UnitSpec
import org.bigbluebutton.api2.IBbbWebApiGWApp

import java.io.{ File, FileOutputStream }
import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.{ ZipEntry, ZipOutputStream }
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

class RoomStateRestoreServiceTest extends UnitSpec {

  private val MeetingId = "183f0bf3a0982a127bdb8161e0c44eb696b3e75c-1554230749920"
  private val UserId = "w_loader"
  private val LoadId = "abcdefghij0123456789"

  // Enough of a PDF for its type to be recognised.
  private val Pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII)

  private val Manifest =
    """{"format":"bbb-room-state","version":1,
      | "presentations":[{"index":1,"name":"deck.pdf","file":"presentations/1/original.pdf",
      |   "current":true,"currentPage":2,"pageCount":3,"downloadable":false,"removable":true}],
      | "notes":{"pinned":true}}""".stripMargin

  private val Annotations =
    """{"pages":{
      | "1":[{"id":"shape:frame","annotationInfo":{"id":"shape:frame","type":"frame","parentId":"page:1","meta":{"presentationId":"old-pres","createdBy":"w_old"}},"wbId":"old-pres/1","userId":"w_old"},
      |      {"id":"shape:child","annotationInfo":{"id":"shape:child","type":"geo","parentId":"shape:frame","props":{"text":"shape:frame is my parent"}},"wbId":"old-pres/1","userId":"w_old"}],
      | "9":[{"id":"shape:beyond","annotationInfo":{"id":"shape:beyond","type":"geo"}}]}}""".stripMargin

  private val Chat =
    """[{"id":"m1","timestamp":1000,"senderId":"w_a","senderName":"Ana","senderRole":"MODERATOR","chatEmphasizedText":true,
      |  "message":"hello","replyToMessageId":"","messageType":"default","metadata":{},"messageAsHtml":"<script>x</script>"},
      | {"id":"m2","timestamp":2000,"senderName":"Bob","senderRole":"OWNER","message":"hi","replyToMessageId":"m1"}]""".stripMargin

  private val Layout =
    """{"server":{"currentLayout":"UNIFIED_LAYOUT","pushLayout":true,"presentationIsOpen":false,"cameraPosition":"contentTop",
      |   "focusedCamera":"w_old_cam","presentationVideoRate":0.5,"screenshareAsContent":false},
      | "client":{"sidebarContentIsOpen":true,"sidebarContentPanel":"shared-notes","unknown":"dropped","selectedLayout":"<img src=x>"}}""".stripMargin

  private val ValidEntries: Seq[(String, Array[Byte])] = Seq(
    "manifest.json" -> Manifest.getBytes(StandardCharsets.UTF_8),
    "presentations/1/original.pdf" -> Pdf,
    "presentations/1/annotations.json" -> Annotations.getBytes(StandardCharsets.UTF_8),
    "chat/public.json" -> Chat.getBytes(StandardCharsets.UTF_8),
    "notes/notes.json" -> """[{"id":"b1","type":"paragraph","content":[]}]""".getBytes(StandardCharsets.UTF_8),
    "layout.json" -> Layout.getBytes(StandardCharsets.UTF_8)
  )

  private class Fixture {
    val presentationDir: File = Files.createTempDirectory("room-state-test").toFile
    val sent: ArrayBuffer[JsonObject] = ArrayBuffer()

    // Only send() is used by the service; everything else of the gateway is left unimplemented.
    private val gw = Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[IBbbWebApiGWApp]), new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
        if (method.getName == "send") sent += JsonParser.parseString(args(1).asInstanceOf[String]).getAsJsonObject
        null
      }
    }).asInstanceOf[IBbbWebApiGWApp]

    val service = new RoomStateRestoreService
    service.setGw(gw)
    service.setPresentationDir(presentationDir.getAbsolutePath)

    def archive(entries: Seq[(String, Array[Byte])]): File = {
      val file = File.createTempFile("room-state", ".zip", presentationDir)
      val zip = new ZipOutputStream(new FileOutputStream(file))
      entries.foreach {
        case (name, content) =>
          zip.putNextEntry(new ZipEntry(name))
          zip.write(content)
          zip.closeEntry()
      }
      zip.close()
      file
    }

    def restore(entries: Seq[(String, Array[Byte])]): RoomStateRestoreService.Result =
      service.restore(archive(entries), MeetingId, UserId, LoadId)

    def refusal(entries: Seq[(String, Array[Byte])]): String =
      intercept[RoomStateException](restore(entries)).getKey

    def names: Seq[String] = sent.map(_.getAsJsonObject("envelope").get("name").getAsString).toSeq

    def body(name: String): JsonObject = sent
      .find(_.getAsJsonObject("envelope").get("name").getAsString == name).get
      .getAsJsonObject("core").getAsJsonObject("body")

    // Presentation directories created for the meeting, if any.
    def storedPresentations: Seq[File] = {
      val meetingDir = new File(new File(presentationDir, MeetingId), MeetingId)
      Option(meetingDir.listFiles()).map(_.toSeq).getOrElse(Seq())
    }
  }

  private def replace(entries: Seq[(String, Array[Byte])], name: String, content: String): Seq[(String, Array[Byte])] =
    entries.filterNot(_._1 == name) :+ (name -> content.getBytes(StandardCharsets.UTF_8))

  "A valid archive" should "be sent to akka-apps in order, addressed to the meeting and the user" in new Fixture {
    restore(ValidEntries)

    names shouldBe Seq(
      "RestoreMeetingStateBeginSysMsg",
      "RestoreGroupChatMessagesSysMsg",
      "RestoreWhiteboardAnnotationsSysMsg",
      "RestoreSharedNotesSysMsg",
      "RestoreMeetingStateEndSysMsg"
    )
    sent.foreach { message =>
      val header = message.getAsJsonObject("core").getAsJsonObject("header")
      header.get("meetingId").getAsString shouldBe MeetingId
      header.get("userId").getAsString shouldBe UserId
    }
    body("RestoreMeetingStateBeginSysMsg").get("notesPinned").getAsBoolean shouldBe true
    body("RestoreSharedNotesSysMsg").getAsJsonArray("contentJson").size shouldBe 1
  }

  it should "store its presentation under a new id and return it for conversion" in new Fixture {
    val result = restore(ValidEntries)

    result.presentations.size shouldBe 1
    val presentation = result.presentations.get(0)
    presentation.name shouldBe "deck.pdf"
    presentation.current shouldBe true
    presentation.file.getName shouldBe s"${presentation.presentationId}.pdf"
    Files.readAllBytes(presentation.file.toPath) shouldBe Pdf

    val begun = body("RestoreMeetingStateBeginSysMsg").getAsJsonArray("presentations").get(0).getAsJsonObject
    begun.get("presentationId").getAsString shouldBe presentation.presentationId
    begun.get("currentPage").getAsInt shouldBe 2
  }

  it should "attach the annotations to the new presentation, with new ids and references kept" in new Fixture {
    val result = restore(ValidEntries)
    val presentationId = result.presentations.get(0).presentationId
    val annotations = body("RestoreWhiteboardAnnotationsSysMsg")

    annotations.get("whiteboardId").getAsString shouldBe s"$presentationId/1"
    val shapes = annotations.getAsJsonArray("annotations").asScala.map(_.getAsJsonObject).toSeq
    shapes.size shouldBe 2

    val frame = shapes.find(_.getAsJsonObject("annotationInfo").get("type").getAsString == "frame").get
    val child = shapes.find(_.getAsJsonObject("annotationInfo").get("type").getAsString == "geo").get
    val frameId = frame.get("id").getAsString

    frameId should (startWith("shape:") and not be "shape:frame")
    frame.getAsJsonObject("annotationInfo").get("id").getAsString shouldBe frameId
    frame.get("wbId").getAsString shouldBe s"$presentationId/1"
    frame.get("userId").getAsString shouldBe UserId
    frame.getAsJsonObject("annotationInfo").getAsJsonObject("meta").get("presentationId").getAsString shouldBe presentationId
    frame.getAsJsonObject("annotationInfo").getAsJsonObject("meta").get("createdBy").getAsString shouldBe UserId
    // The page a shape is on does not change; the shape it is in follows its new id.
    frame.getAsJsonObject("annotationInfo").get("parentId").getAsString shouldBe "page:1"
    child.getAsJsonObject("annotationInfo").get("parentId").getAsString shouldBe frameId
    // Text that merely mentions an id is left alone.
    child.getAsJsonObject("annotationInfo").getAsJsonObject("props").get("text").getAsString shouldBe "shape:frame is my parent"
  }

  it should "drop annotations of pages the presentation does not have" in new Fixture {
    restore(ValidEntries)
    names.count(_ == "RestoreWhiteboardAnnotationsSysMsg") shouldBe 1
  }

  it should "send chat messages with the expected fields only" in new Fixture {
    restore(ValidEntries)
    val messages = body("RestoreGroupChatMessagesSysMsg").getAsJsonArray("messages").asScala.map(_.getAsJsonObject).toSeq

    messages.map(_.get("senderName").getAsString) shouldBe Seq("Ana", "Bob")
    messages.map(_.get("timestamp").getAsLong) shouldBe Seq(1000L, 2000L)
    // An unknown role is a viewer, and saved HTML is not passed on.
    messages.map(_.get("senderRole").getAsString) shouldBe Seq("MODERATOR", "VIEWER")
    messages.foreach(_.keySet.asScala shouldBe Set("id", "timestamp", "senderId", "senderName", "senderRole",
      "chatEmphasizedText", "message", "replyToMessageId", "messageType", "metadata"))
    messages(1).get("replyToMessageId").getAsString shouldBe "m1"
  }

  it should "pass on the layout, without the camera of the saved meeting and without unknown client values" in new Fixture {
    val result = restore(ValidEntries)
    val layout = body("RestoreMeetingStateBeginSysMsg").getAsJsonObject("layout")

    layout.get("currentLayout").getAsString shouldBe "UNIFIED_LAYOUT"
    layout.get("presentationIsOpen").getAsBoolean shouldBe false
    layout.get("focusedCamera").getAsString shouldBe "none"

    val client = JsonParser.parseString(result.clientLayoutJson).getAsJsonObject
    client.get("sidebarContentPanel").getAsString shouldBe "shared-notes"
    client.get("sidebarContentIsOpen").getAsBoolean shouldBe true
    client.has("unknown") shouldBe false
    client.get("selectedLayout").getAsString shouldBe ""
  }

  "An archive without chat, notes or presentations" should "leave those alone" in new Fixture {
    restore(Seq("manifest.json" -> """{"format":"bbb-room-state","version":1}""".getBytes(StandardCharsets.UTF_8)))
    names shouldBe Seq("RestoreMeetingStateBeginSysMsg", "RestoreMeetingStateEndSysMsg")
  }

  "A refused archive" should "leave the meeting untouched" in new Fixture {
    // The presentation is valid and gets stored before the next one is found to be something else.
    val manifest = Manifest.replace(
      """"removable":true}]""",
      """"removable":true},{"index":2,"name":"notes.pdf","file":"presentations/2/original.pdf","pageCount":1}]"""
    )
    val entries = replace(ValidEntries, "manifest.json", manifest) :+ ("presentations/2/original.pdf" -> "just text".getBytes)

    refusal(entries) shouldBe "invalidPresentation"
    sent shouldBe empty
    storedPresentations shouldBe empty
  }

  it should "be refused when it is not a room state archive" in new Fixture {
    refusal(Seq("readme.txt" -> "hello".getBytes)) shouldBe "invalid"
    refusal(replace(ValidEntries, "manifest.json", """{"format":"something-else","version":1}""")) shouldBe "invalid"
    refusal(replace(ValidEntries, "manifest.json", """["not","an","object"]""")) shouldBe "invalid"
    refusal(replace(ValidEntries, "manifest.json", "{ not json")) shouldBe "invalid"

    val notAZip = File.createTempFile("room-state", ".zip", presentationDir)
    Files.write(notAZip.toPath, "not a zip".getBytes)
    intercept[RoomStateException](service.restore(notAZip, MeetingId, UserId, LoadId)).getKey shouldBe "invalid"
    sent shouldBe empty
  }

  it should "be refused when it was saved by another version" in new Fixture {
    refusal(replace(ValidEntries, "manifest.json", """{"format":"bbb-room-state","version":2}""")) shouldBe "unsupportedVersion"
  }

  it should "be refused when a presentation file is named to escape its directory" in new Fixture {
    for (file <- Seq("presentations/1/../../../etc/original.pdf", "/etc/passwd", "presentations/1/original.pdf/../x", "presentations/2/original.pdf", "presentations/1/original.PDF")) {
      val manifest = Manifest.replace("presentations/1/original.pdf", file)
      refusal(replace(ValidEntries, "manifest.json", manifest)) shouldBe "invalid"
    }
    storedPresentations shouldBe empty
  }

  it should "be refused when it holds more than allowed" in new Fixture {
    service.setMaxPresentations(0)
    refusal(ValidEntries) shouldBe "tooManyPresentations"
    service.setMaxPresentations(20)

    service.setMaxChatMessages(1)
    refusal(ValidEntries) shouldBe "tooManyChatMessages"
    service.setMaxChatMessages(5000)

    service.setMaxAnnotationsPerPresentation(1)
    refusal(ValidEntries) shouldBe "tooManyAnnotations"
    service.setMaxAnnotationsPerPresentation(50000)

    service.setMaxEntries(3)
    refusal(ValidEntries) shouldBe "invalid"
    sent shouldBe empty
  }

  it should "be refused when it, or a file in it, is too large" in new Fixture {
    service.setMaxPresentationFileSize(Pdf.length - 1)
    refusal(ValidEntries) shouldBe "tooLarge"
    service.setMaxPresentationFileSize(30000000)

    service.setMaxJsonEntrySize(10)
    refusal(ValidEntries) shouldBe "tooLarge"
    service.setMaxJsonEntrySize(20971520)

    // The size is counted while reading, whatever the headers of the zip say.
    service.setMaxUncompressedSize(Manifest.length + 10)
    refusal(ValidEntries) shouldBe "tooLarge"
    service.setMaxUncompressedSize(524288000)

    service.setMaxZipSize(10)
    refusal(ValidEntries) shouldBe "tooLarge"

    sent shouldBe empty
    storedPresentations shouldBe empty
  }

  it should "be refused when its JSON is nested to exhaust the stack" in new Fixture {
    val deep = "[" * 100000 + "]" * 100000
    refusal(replace(ValidEntries, "notes/notes.json", deep)) shouldBe "invalid"
    // Brackets inside strings are not nesting.
    restore(replace(ValidEntries, "notes/notes.json", s"""[{"text":"${"[" * 1000}"}]"""))
  }
}
