import React, { useEffect, useState } from 'react';
import { defineMessages, useIntl } from 'react-intl';
import ModalSimple from '/imports/ui/components/common/modal/simple/component';
import Button from '/imports/ui/components/common/button/component';
import logger from '/imports/startup/client/logger';
import {
  CURRENT_STATE_ID,
  ClientRoomState,
  RoomStateLoadError,
  SavedRoomState,
  fetchSavedStates,
  loadSavedState,
  loadStateFromFile,
  loadStateFromUrl,
} from '../service';
import Styled from './styles';

const intlMessages = defineMessages({
  title: {
    id: 'app.roomState.load.title',
    description: 'Title of the load room state modal',
  },
  savedStatesLabel: {
    id: 'app.roomState.load.savedStatesLabel',
    description: 'Heading of the list of room states saved on the server',
  },
  currentState: {
    id: 'app.roomState.load.currentState',
    description: 'Label of the room state saved during this session, followed by its time',
  },
  fileLabel: {
    id: 'app.roomState.load.fileLabel',
    description: 'Label of the file picker for a saved room state',
  },
  orDivider: {
    id: 'app.roomState.load.or',
    description: 'Separator between the file picker and the URL field',
  },
  urlLabel: {
    id: 'app.roomState.load.urlLabel',
    description: 'Label of the field for the URL of a saved room state',
  },
  warning: {
    id: 'app.roomState.load.warning',
    description: 'Warning that loading replaces the current content of the room',
  },
  cancelLabel: {
    id: 'app.roomState.load.cancel',
    description: 'Label of the cancel button',
  },
  confirmLabel: {
    id: 'app.roomState.load.confirm',
    description: 'Label of the button that replaces the room content with the saved state',
  },
  loadingLabel: {
    id: 'app.roomState.load.loading',
    description: 'Label of the confirm button while the saved state is being loaded',
  },
  errorInvalid: {
    id: 'app.roomState.load.error.invalid',
    description: 'Error shown when the file is not a room state file',
  },
  errorUnsupportedVersion: {
    id: 'app.roomState.load.error.unsupportedVersion',
    description: 'Error shown when the file was saved by a version that is not supported',
  },
  errorTooLarge: {
    id: 'app.roomState.load.error.tooLarge',
    description: 'Error shown when the file, or something in it, is too large',
  },
  errorInvalidPresentation: {
    id: 'app.roomState.load.error.invalidPresentation',
    description: 'Error shown when a presentation of the file is of an unsupported type',
  },
  errorTooMuchContent: {
    id: 'app.roomState.load.error.tooMuchContent',
    description: 'Error shown when the file has more presentations, messages or annotations than allowed',
  },
  errorDownloadFailed: {
    id: 'app.roomState.load.error.downloadFailed',
    description: 'Error shown when the file could not be downloaded from the given URL',
  },
  errorNotFound: {
    id: 'app.roomState.load.error.notFound',
    description: 'Error shown when the chosen saved room state no longer exists',
  },
  errorBusy: {
    id: 'app.roomState.load.error.busy',
    description: 'Error shown when another room state is being loaded or too many were loaded',
  },
  errorFailed: {
    id: 'app.roomState.load.error.failed',
    description: 'Error shown when loading failed for another reason',
  },
});

// Reasons reported by the server (see RoomStateRestoreService in bbb-common-web).
const errorMessages: Record<string, { id: string; description: string }> = {
  invalid: intlMessages.errorInvalid,
  unsupportedVersion: intlMessages.errorUnsupportedVersion,
  tooLarge: intlMessages.errorTooLarge,
  invalidPresentation: intlMessages.errorInvalidPresentation,
  tooManyPresentations: intlMessages.errorTooMuchContent,
  tooManyChatMessages: intlMessages.errorTooMuchContent,
  tooManyAnnotations: intlMessages.errorTooMuchContent,
  invalidUrl: intlMessages.errorDownloadFailed,
  downloadFailed: intlMessages.errorDownloadFailed,
  notFound: intlMessages.errorNotFound,
  busy: intlMessages.errorBusy,
};

interface LoadRoomStateModalProps {
  onClose: () => void;
  onLoaded: (clientState: Partial<ClientRoomState>) => void;
}

const LoadRoomStateModal: React.FC<LoadRoomStateModalProps> = ({ onClose, onLoaded }) => {
  const intl = useIntl();
  const [savedStates, setSavedStates] = useState<SavedRoomState[]>([]);
  const [savedStateId, setSavedStateId] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [errorKey, setErrorKey] = useState<string | null>(null);

  // The states kept on the server come first; without any, the dialog only
  // offers a file or a URL.
  useEffect(() => {
    let isOpen = true;
    fetchSavedStates()
      .then((states) => { if (isOpen) setSavedStates(states); })
      .catch((error) => logger.warn({
        logCode: 'room_state_list_failed',
        extraInfo: { errorMessage: (error as Error)?.message },
      }, 'Listing the saved room states failed'));
    return () => { isOpen = false; };
  }, []);

  const trimmedUrl = url.trim();
  const canLoad = !isLoading && (savedStateId !== null || file !== null || trimmedUrl.length > 0);

  const describe = (state: SavedRoomState) => {
    const savedAt = intl.formatDate(state.savedAt, { dateStyle: 'medium', timeStyle: 'short' });
    return state.id === CURRENT_STATE_ID
      ? intl.formatMessage(intlMessages.currentState, { 0: savedAt })
      : `${state.name} (${savedAt})`;
  };

  const load = async () => {
    setIsLoading(true);
    setErrorKey(null);
    try {
      let clientState;
      if (savedStateId !== null) {
        clientState = await loadSavedState(savedStateId);
      } else if (file !== null) {
        clientState = await loadStateFromFile(file);
      } else {
        clientState = await loadStateFromUrl(trimmedUrl);
      }
      onLoaded(clientState);
      onClose();
    } catch (error) {
      const messageKey = error instanceof RoomStateLoadError ? error.messageKey : 'failed';
      logger.warn({
        logCode: 'room_state_load_failed',
        extraInfo: { messageKey, errorMessage: (error as Error)?.message },
      }, 'Loading the room state failed');
      setErrorKey(messageKey);
      setIsLoading(false);
    }
  };

  return (
    <ModalSimple
      title={intl.formatMessage(intlMessages.title)}
      modalIsOpen
      onRequestClose={onClose}
      dataTest="roomStateLoadModal"
    >
      <Styled.Container>
        {savedStates.length > 0 && (
          <>
            <Styled.StateList role="radiogroup" aria-label={intl.formatMessage(intlMessages.savedStatesLabel)}>
              <Styled.StateListLegend>
                {intl.formatMessage(intlMessages.savedStatesLabel)}
              </Styled.StateListLegend>
              {savedStates.map((state) => (
                <Styled.StateOption key={state.id} data-test="roomStateSavedState">
                  <input
                    type="radio"
                    name="room-state-saved"
                    value={state.id}
                    checked={savedStateId === state.id}
                    disabled={isLoading}
                    onChange={() => setSavedStateId(state.id)}
                  />
                  {describe(state)}
                </Styled.StateOption>
              ))}
            </Styled.StateList>
            <Styled.Divider>{intl.formatMessage(intlMessages.orDivider)}</Styled.Divider>
          </>
        )}

        <Styled.Label>
          {intl.formatMessage(intlMessages.fileLabel)}
          <input
            type="file"
            accept=".zip,application/zip"
            data-test="roomStateLoadFileInput"
            disabled={isLoading}
            onChange={(event) => {
              setFile(event.target.files?.[0] ?? null);
              setSavedStateId(null);
            }}
          />
        </Styled.Label>

        <Styled.Divider>{intl.formatMessage(intlMessages.orDivider)}</Styled.Divider>

        <Styled.Label>
          {intl.formatMessage(intlMessages.urlLabel)}
          <Styled.UrlInput
            type="url"
            value={url}
            placeholder="https://"
            data-test="roomStateLoadUrlInput"
            disabled={isLoading || file !== null}
            onChange={(event) => {
              setUrl(event.target.value);
              setSavedStateId(null);
            }}
          />
        </Styled.Label>

        <Styled.Warning>{intl.formatMessage(intlMessages.warning)}</Styled.Warning>

        {errorKey !== null && (
          <Styled.ErrorText role="alert" data-test="roomStateLoadError">
            {intl.formatMessage(errorMessages[errorKey] ?? intlMessages.errorFailed)}
          </Styled.ErrorText>
        )}

        <Styled.Actions>
          <Button
            label={intl.formatMessage(intlMessages.cancelLabel)}
            onClick={onClose}
            color="default"
            size="sm"
            disabled={isLoading}
            dataTest="roomStateLoadCancel"
          />
          <Button
            label={intl.formatMessage(isLoading ? intlMessages.loadingLabel : intlMessages.confirmLabel)}
            onClick={load}
            color="danger"
            size="sm"
            disabled={!canLoad}
            dataTest="roomStateLoadConfirm"
          />
        </Styled.Actions>
      </Styled.Container>
    </ModalSimple>
  );
};

export default LoadRoomStateModal;
