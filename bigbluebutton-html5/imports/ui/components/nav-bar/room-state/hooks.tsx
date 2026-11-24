import React, { useCallback, useMemo, useState } from 'react';
import { defineMessages, useIntl } from 'react-intl';
import { useMutation } from '@apollo/client';
import { layoutDispatch, layoutSelectInput } from '/imports/ui/components/layout/context';
import { ACTIONS, PANELS } from '/imports/ui/components/layout/enums';
import { Input } from '/imports/ui/components/layout/layoutTypes';
import { notify } from '/imports/ui/services/notification';
import { useIsRoomStateEnabled } from '/imports/ui/services/features';
import useMeeting from '/imports/ui/core/hooks/useMeeting';
import useSettings from '/imports/ui/services/settings/hooks/useSettings';
import { SETTINGS } from '/imports/ui/services/settings/enums';
import logger from '/imports/startup/client/logger';
import { MEETING_STATE_SAVE } from './mutations';
import LoadRoomStateModal from './load-modal/component';
import SaveRoomStateModal from './save-modal/component';
import {
  ClientRoomState,
  downloadSavedState,
  generateJobId,
  waitForSavedState,
} from './service';

const intlMessages = defineMessages({
  saveLabel: {
    id: 'app.roomState.save.label',
    description: 'Menu item that opens the save room state modal',
  },
  saveStarted: {
    id: 'app.roomState.save.started',
    description: 'Toast shown while the room state is being saved',
  },
  saveReady: {
    id: 'app.roomState.save.ready',
    description: 'Toast shown when the room state has been saved on the server',
  },
  downloadReady: {
    id: 'app.roomState.save.downloadReady',
    description: 'Toast shown when the room state file is ready and its download starts',
  },
  saveFailed: {
    id: 'app.roomState.save.failed',
    description: 'Toast shown when the room state could not be saved',
  },
  loadLabel: {
    id: 'app.roomState.load.label',
    description: 'Menu item that loads a saved room state',
  },
});

interface RoomStateMenuItem {
  key: string;
  icon: string;
  dataTest: string;
  label: string;
  disabled?: boolean;
  onClick: () => void;
}

interface RoomStateMenu {
  // Entries for the options menu; none for a viewer or when the feature is off.
  items: RoomStateMenuItem[];
  // The save or the load dialog, to render next to the menu.
  modal: React.ReactNode;
}

/**
 * The "Save room state" and "Load room state" commands of a moderator, for the
 * options menu of the navigation bar.
 */
const useRoomStateMenu = (amIModerator: boolean): RoomStateMenu => {
  const intl = useIntl();
  const isRoomStateEnabled = useIsRoomStateEnabled();
  const [isSaving, setIsSaving] = useState(false);
  const [isSaveModalOpen, setIsSaveModalOpen] = useState(false);
  const [isLoadModalOpen, setIsLoadModalOpen] = useState(false);
  const layoutContextDispatch = layoutDispatch();
  const [meetingStateSave] = useMutation(MEETING_STATE_SAVE);

  const sidebarNavigationIsOpen = layoutSelectInput((i: Input) => i.sidebarNavigation.isOpen);
  const sidebarContentIsOpen = layoutSelectInput((i: Input) => i.sidebarContent.isOpen);
  const sidebarContentPanel = layoutSelectInput((i: Input) => i.sidebarContent.sidebarContentPanel);
  const presentationIsOpen = layoutSelectInput((i: Input) => i.presentation.isOpen);
  const cameraDockPosition = layoutSelectInput((i: Input) => i.cameraDock.position);
  const { selectedLayout } = useSettings(SETTINGS.LAYOUT) as { selectedLayout: string };
  const { data: meeting } = useMeeting((m) => ({ componentsFlags: m.componentsFlags }));
  const notesPinned = !!meeting?.componentsFlags?.isSharedNotesPinned;

  // Keeps the state on the server for this session, or has the server build an
  // archive of it that is then downloaded; the latter leaves the kept state alone.
  const save = useCallback(async (download: boolean) => {
    const jobId = generateJobId();
    const clientState: ClientRoomState = {
      selectedLayout: selectedLayout ?? '',
      sidebarNavigationIsOpen: !!sidebarNavigationIsOpen,
      sidebarContentIsOpen: !!sidebarContentIsOpen,
      sidebarContentPanel: sidebarContentPanel ?? '',
      presentationIsOpen: !!presentationIsOpen,
      cameraDockPosition: cameraDockPosition ?? '',
      notesPinned,
    };

    setIsSaving(true);
    notify(intl.formatMessage(intlMessages.saveStarted), 'info', 'download');
    try {
      await meetingStateSave({ variables: { jobId, clientState, download } });
      await waitForSavedState(jobId);
      if (download) downloadSavedState(jobId);
      notify(
        intl.formatMessage(download ? intlMessages.downloadReady : intlMessages.saveReady),
        'success',
        'download',
      );
    } catch (error) {
      logger.error({
        logCode: 'room_state_save_failed',
        extraInfo: { download, errorMessage: (error as Error)?.message },
      }, 'Saving the room state failed');
      notify(intl.formatMessage(intlMessages.saveFailed), 'error', 'warning');
    } finally {
      setIsSaving(false);
    }
  }, [
    selectedLayout, sidebarNavigationIsOpen, sidebarContentIsOpen, sidebarContentPanel,
    presentationIsOpen, cameraDockPosition, notesPinned, intl.locale,
  ]);

  // The server restored what it keeps (presentations, chat, notes, the layout
  // shared with everyone). Which panels are open only exists in each client:
  // bring back the ones the saved room had, for the moderator who loaded it.
  const applyClientState = useCallback((clientState: Partial<ClientRoomState>) => {
    const knownPanels: string[] = Object.values(PANELS);
    const panel = clientState.sidebarContentPanel ?? '';

    if (typeof clientState.sidebarNavigationIsOpen === 'boolean') {
      layoutContextDispatch({
        type: ACTIONS.SET_SIDEBAR_NAVIGATION_IS_OPEN,
        value: clientState.sidebarNavigationIsOpen,
      });
    }

    if (clientState.sidebarContentIsOpen === false) {
      layoutContextDispatch({ type: ACTIONS.SET_SIDEBAR_CONTENT_IS_OPEN, value: false });
      layoutContextDispatch({ type: ACTIONS.SET_SIDEBAR_CONTENT_PANEL, value: PANELS.NONE });
      layoutContextDispatch({ type: ACTIONS.SET_ID_CHAT_OPEN, value: undefined });
    } else if (clientState.sidebarContentIsOpen === true && knownPanels.includes(panel)) {
      layoutContextDispatch({ type: ACTIONS.SET_SIDEBAR_CONTENT_IS_OPEN, value: true });
      layoutContextDispatch({ type: ACTIONS.SET_SIDEBAR_CONTENT_PANEL, value: panel });
      layoutContextDispatch({
        type: ACTIONS.SET_ID_CHAT_OPEN,
        value: panel === PANELS.CHAT
          ? window.meetingClientSettings.public.chat.public_group_id
          : undefined,
      });
    }

    if (typeof clientState.presentationIsOpen === 'boolean') {
      layoutContextDispatch({
        type: ACTIONS.SET_PRESENTATION_IS_OPEN,
        value: clientState.presentationIsOpen,
      });
    }
  }, [layoutContextDispatch]);

  const actions: RoomStateMenuItem[] = useMemo(() => [
    {
      key: 'room-state-save',
      icon: 'download',
      dataTest: 'roomStateSave',
      label: intl.formatMessage(intlMessages.saveLabel),
      disabled: isSaving,
      onClick: () => setIsSaveModalOpen(true),
    },
    {
      key: 'room-state-load',
      icon: 'upload',
      dataTest: 'roomStateLoad',
      label: intl.formatMessage(intlMessages.loadLabel),
      onClick: () => setIsLoadModalOpen(true),
    },
  ], [isSaving, intl.locale]);

  if (!amIModerator || !isRoomStateEnabled) return { items: [], modal: null };

  let modal: React.ReactNode = null;
  if (isSaveModalOpen) {
    modal = (
      <SaveRoomStateModal
        onClose={() => setIsSaveModalOpen(false)}
        onConfirm={save}
      />
    );
  } else if (isLoadModalOpen) {
    modal = (
      <LoadRoomStateModal
        onClose={() => setIsLoadModalOpen(false)}
        onLoaded={applyClientState}
      />
    );
  }

  return { items: actions, modal };
};

export default useRoomStateMenu;
