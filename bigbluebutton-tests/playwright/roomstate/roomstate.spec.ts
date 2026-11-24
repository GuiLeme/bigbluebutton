import { test } from '../core/setup/fixtures';
import { RoomState } from './roomstate';

test.describe('Room state', { tag: '@ci' }, () => {
  test('Save the state of a room and return to it, in the meeting and in a later one', async ({
    browser,
    browserName,
    context,
    page,
  }, testInfo) => {
    test.skip(browserName !== 'chromium', 'Draws on the whiteboard, which is only reliable on Chromium');
    const roomState = new RoomState(browser, context);
    // A saved state outlives its meeting as a recording, so the meeting has to be a recorded one.
    await roomState.initModPage(page, { testInfo, createParameter: 'record=true' });
    await roomState.initUserPage(context, { testInfo });

    await roomState.prepareRoom();
    await roomState.saveRoomState();
    await roomState.returnToSavedState();

    const downloaded = await roomState.downloadLaterState();
    await roomState.loadDownloadedState(downloaded);

    const recordId = await roomState.endMeetingAndFindItsState();
    await roomState.startNewRoom([recordId]);
    await roomState.loadStateOfEarlierMeeting();
    await roomState.checkLoadedRoom();
    await roomState.saveInNewRoom();
  });
});
