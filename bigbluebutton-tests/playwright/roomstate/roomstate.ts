import { expect } from '@playwright/test';
import { readFileSync } from 'fs';

import { sendPublicMessages } from '../chat/util';
import { ELEMENT_WAIT_EXTRA_LONG_TIME, ELEMENT_WAIT_LONGER_TIME, UPLOAD_PDF_WAIT_TIME } from '../core/constants';
import { elements as e } from '../core/elements';
import { getRecordings } from '../core/endpoints';
import { endMeetings } from '../core/helpers';
import { Page } from '../core/page';
import { listPresentations, skipSlide, uploadSinglePresentation } from '../presentation/util';
import { getBlockNoteEditorLocator, startSharedNotesBlockNote } from '../sharednotes/blocknote/util';
import { MultiUsers } from '../user/multiusers';

const DEFAULT_PRESENTATION = 'default.pdf';
const PRESENTATION = 'sample.pdf';
const MODERATOR_MESSAGE = 'Welcome to the first class';
const ATTENDEE_MESSAGE = 'Thank you, see you next week';
const LATER_MESSAGE = 'Written after the state was saved';
const DOWNLOADED_MESSAGE = 'Written before the state was downloaded';
const NOTES = 'Homework for next week';

// Time for the server to build the archive, which the client polls for.
const SAVE_TIMEOUT = 60000;
// Time for the saved state to be listed as a recording once its meeting ended.
const PUBLISH_TIMEOUT = 30000;

interface RecordingsResponse {
  response: {
    recordings?: {
      recording?: { recordID: string[]; name: string[]; playback: { format: { type: string[]; url: string[] }[] }[] }[];
    }[];
  };
}

async function drawLine(testPage: Page) {
  await testPage.waitForSelector(e.whiteboard, ELEMENT_WAIT_LONGER_TIME);
  await testPage.waitAndClick(e.wbPencilShape);
  const box = await testPage.page.locator(e.whiteboard).boundingBox();
  if (!box) throw new Error('whiteboard boundingBox is null');
  await testPage.page.mouse.move(box.x + 0.3 * box.width, box.y + 0.3 * box.height, { steps: 20 });
  await testPage.page.mouse.down();
  await testPage.page.mouse.move(box.x + 0.6 * box.width, box.y + 0.6 * box.height, { steps: 20 });
  await testPage.page.mouse.up();
  await testPage.hasElementCount(e.wbDraw, 1, 'should display the drawn line');
}

async function openLoadDialog(testPage: Page) {
  await testPage.waitAndClick(e.optionsButton);
  await testPage.waitAndClick(e.roomStateLoad);
  await testPage.hasElement(e.roomStateLoadModal, 'should display the load dialog');
}

// Asks for the save dialog and confirms it with the given option chosen.
async function confirmSaveDialog(testPage: Page, option: string) {
  await testPage.waitAndClick(e.optionsButton);
  await testPage.waitAndClick(e.roomStateSave);
  await testPage.hasElement(e.roomStateSaveModal, 'should display the save dialog');
  await testPage.hasText(e.roomStateSaveSession, 'Save in this session', 'should label the session option');
  await testPage.hasText(e.roomStateSaveDownload, 'Download as a .zip file', 'should label the download option');
  await testPage.waitAndClick(option);
  await testPage.waitAndClick(e.roomStateSaveConfirm);
  await testPage.wasRemoved(e.roomStateSaveModal, 'should close the save dialog once confirmed');
}

// Loads the saved state whose label contains the given text.
async function loadSavedState(testPage: Page, label: string) {
  await openLoadDialog(testPage);
  await testPage.page.locator(e.roomStateSavedState, { hasText: label }).click();
  await testPage.waitAndClick(e.roomStateLoadConfirm);
  await testPage.wasRemoved(
    e.roomStateLoadModal,
    'should close the load dialog once the state is loaded',
    UPLOAD_PDF_WAIT_TIME,
  );
}

export class RoomState extends MultiUsers {
  // Fills a room: a presentation with a drawing on its first two slides and the
  // second one shown, a chat between two users, and shared notes left open.
  async prepareRoom() {
    await uploadSinglePresentation(this.modPage, PRESENTATION);
    await drawLine(this.modPage);
    await skipSlide(this.modPage);
    await drawLine(this.modPage);

    await sendPublicMessages(this.modPage, [MODERATOR_MESSAGE]);
    await sendPublicMessages(this.userPage, [ATTENDEE_MESSAGE]);
    await this.modPage.hasText(e.chatMessages, ATTENDEE_MESSAGE, 'should display the message of the attendee');

    await startSharedNotesBlockNote(this.modPage);
    await getBlockNoteEditorLocator(this.modPage).click();
    await this.modPage.page.keyboard.type(NOTES);
    // What the attendee sees has been through the server: the notes are there to save.
    await startSharedNotesBlockNote(this.userPage);
    await expect(getBlockNoteEditorLocator(this.userPage), 'should display the notes for the attendee').toContainText(
      NOTES,
    );
  }

  // Saving keeps the state on the server: nothing is downloaded.
  async saveRoomState(testPage: Page = this.modPage) {
    await confirmSaveDialog(testPage, e.roomStateSaveSession);
    await expect(testPage.page.getByText('Room state saved'), 'should tell the state was saved').toBeVisible({
      timeout: SAVE_TIMEOUT,
    });
  }

  // Downloading builds an archive of its own: the state kept on the server is
  // not replaced. Returns the path of the downloaded file.
  async downloadRoomState(testPage: Page = this.modPage): Promise<string> {
    const downloadPromise = testPage.page.waitForEvent('download', { timeout: SAVE_TIMEOUT });
    await confirmSaveDialog(testPage, e.roomStateSaveDownload);
    const download = await downloadPromise;
    expect(download.suggestedFilename(), 'should download a zip file').toMatch(/^room-state-.+\.zip$/);
    const filePath = await download.path();
    expect(filePath, 'should have the downloaded file').toBeTruthy();
    return filePath;
  }

  // Changes the room, downloads its state, and checks the state saved in the
  // session is still the earlier one. Returns the path of the downloaded file.
  async downloadLaterState(): Promise<string> {
    await sendPublicMessages(this.modPage, [DOWNLOADED_MESSAGE]);
    await this.modPage.hasText(e.chatMessages, DOWNLOADED_MESSAGE, 'should display the message to download');
    const filePath = await this.downloadRoomState();

    await openLoadDialog(this.modPage);
    await this.modPage.hasElementCount(e.roomStateSavedState, 1, 'should still list one state saved in this session');
    await this.modPage.press('Escape');
    return filePath;
  }

  // The session state does not have the message written before the download;
  // the downloaded file does.
  async loadDownloadedState(filePath: string) {
    await loadSavedState(this.modPage, 'Saved in this session');
    await this.modPage.waitAndClick(e.messagesSidebarButton);
    await this.modPage.hasText(e.chatMessages, ATTENDEE_MESSAGE, 'should display the saved chat');
    await expect(
      this.modPage.page.locator(e.chatMessages),
      'should not have saved in the session what was only downloaded',
    ).not.toContainText(DOWNLOADED_MESSAGE);

    await openLoadDialog(this.modPage);
    await this.modPage.page.locator(e.roomStateLoadFileInput).setInputFiles({
      name: 'room-state.zip',
      mimeType: 'application/zip',
      buffer: readFileSync(filePath),
    });
    await this.modPage.waitAndClick(e.roomStateLoadConfirm);
    await this.modPage.wasRemoved(
      e.roomStateLoadModal,
      'should close the load dialog once the file is loaded',
      UPLOAD_PDF_WAIT_TIME,
    );
    // The chat was open when the file was made: so it is once the file is loaded.
    await this.modPage.hasText(e.chatMessages, DOWNLOADED_MESSAGE, 'should display the chat of the downloaded file');
    await expect(this.modPage.page.locator(e.skipSlide), 'should be on the slide of the downloaded file').toHaveValue(
      '2',
      { timeout: UPLOAD_PDF_WAIT_TIME },
    );
  }

  // Moves the room away from what was saved, then goes back to it.
  async returnToSavedState() {
    await skipSlide(this.modPage);
    await sendPublicMessages(this.modPage, [LATER_MESSAGE]);
    await this.modPage.waitAndClick(e.messagesSidebarButton);
    await this.modPage.hasText(e.chatMessages, LATER_MESSAGE, 'should display the message written after the save');

    await openLoadDialog(this.modPage);
    await this.modPage.hasElementCount(e.roomStateSavedState, 1, 'should list the state saved in this session only');
    await this.modPage.press('Escape');
    await loadSavedState(this.modPage, 'Saved in this session');

    await expect(this.modPage.page.locator(e.skipSlide), 'should be back on the saved slide').toHaveValue('2', {
      timeout: UPLOAD_PDF_WAIT_TIME,
    });
    await this.modPage.waitAndClick(e.messagesSidebarButton);
    await this.modPage.hasText(e.chatMessages, ATTENDEE_MESSAGE, 'should display the saved chat');
    await expect(
      this.modPage.page.locator(e.chatMessages),
      'should not display what was written after the save',
    ).not.toContainText(LATER_MESSAGE);
  }

  // Ends the meeting and returns the record id its saved state is published under.
  async endMeetingAndFindItsState(): Promise<string> {
    await endMeetings(this.modPage.page.request, [this.modPage.meetingId]);

    let recordId = '';
    await expect(async () => {
      const { data } = await getRecordings(this.modPage.meetingId);
      const recordings = (data as RecordingsResponse).response.recordings?.[0]?.recording ?? [];
      const withState = recordings.find((recording) =>
        recording.playback[0].format.some((format) => format.type[0] === 'state'),
      );
      expect(withState, 'getRecordings should list the meeting with a state format').toBeTruthy();
      [recordId] = withState!.recordID;
    }).toPass({ timeout: PUBLISH_TIMEOUT });
    return recordId;
  }

  // Joins a new meeting, created with the saved states it may load.
  async startNewRoom(stateRecordIds: string[]) {
    await this.initModPage2(this.context, {
      fullName: 'Teacher',
      useModMeetingId: false,
      createParameter: `record=true&roomStateRecordIDs=${stateRecordIds.join(',')}`,
    });
    expect(this.modPage2.meetingId, 'the state should be loaded into another meeting').not.toBe(this.modPage.meetingId);
  }

  async loadStateOfEarlierMeeting() {
    await openLoadDialog(this.modPage2);
    await this.modPage2.hasElementCount(e.roomStateSavedState, 1, 'should list the state of the earlier meeting');
    await this.modPage2.press('Escape');
    // A saved state is listed under the name of the meeting it was saved in.
    await loadSavedState(this.modPage2, this.modPage.meetingId);
  }

  async checkLoadedRoom() {
    const page = this.modPage2;

    // The saved room had the shared notes open: so has the room it was loaded into.
    await expect(
      getBlockNoteEditorLocator(page),
      'should open the shared notes panel and display the saved notes',
    ).toContainText(NOTES, { timeout: ELEMENT_WAIT_EXTRA_LONG_TIME });

    // The presentation that was shown is shown again, on the slide it was left at.
    await expect(page.page.locator(e.skipSlide), 'should display the second slide').toHaveValue('2', {
      timeout: UPLOAD_PDF_WAIT_TIME,
    });
    await page.hasElementCount(e.wbDraw, 1, 'should display the drawing of the second slide');
    await page.waitAndClick(e.prevSlide);
    await expect(page.page.locator(e.skipSlide)).toHaveValue('1');
    await page.hasElementCount(e.wbDraw, 1, 'should display the drawing of the first slide');
    // The saved room had its default presentation and the uploaded one. A third
    // one would be the default presentation of this meeting, left behind.
    expect((await listPresentations(page)).sort(), 'should list the presentations of the saved room only').toEqual([
      DEFAULT_PRESENTATION,
      PRESENTATION,
    ]);

    // The chat comes back with the names of who wrote it.
    await page.waitAndClick(e.messagesSidebarButton);
    await page.hasText(e.chatMessages, MODERATOR_MESSAGE, 'should display the message of the moderator');
    await page.hasText(e.chatMessages, ATTENDEE_MESSAGE, 'should display the message of the attendee');
    await page.hasText(e.chatMessages, 'Attendee', 'should display the name of the attendee');
    // What a later meeting gets is the state saved in the session, not a downloaded one.
    await expect(
      page.page.locator(e.chatMessages),
      'should not display what was only in the downloaded state',
    ).not.toContainText(DOWNLOADED_MESSAGE);
  }

  // A state saved in the new meeting is offered next to the earlier one.
  async saveInNewRoom() {
    await this.saveRoomState(this.modPage2);
    await openLoadDialog(this.modPage2);
    await this.modPage2.hasElementCount(e.roomStateSavedState, 2, 'should list the earlier state and the new one');
    await this.modPage2.press('Escape');
  }
}
