import React, { useState } from 'react';
import { defineMessages, useIntl } from 'react-intl';
import ModalSimple from '/imports/ui/components/common/modal/simple/component';
import Button from '/imports/ui/components/common/button/component';
import Styled from './styles';

const intlMessages = defineMessages({
  title: {
    id: 'app.roomState.save.title',
    description: 'Title of the save room state modal',
  },
  sessionOption: {
    id: 'app.roomState.save.sessionOption',
    description: 'Option that keeps the saved room state on the server, for this session',
  },
  sessionDescription: {
    id: 'app.roomState.save.sessionDescription',
    description: 'Explains what saving the room state in the session does',
  },
  downloadOption: {
    id: 'app.roomState.save.downloadOption',
    description: 'Option that downloads the room state as a zip file',
  },
  downloadDescription: {
    id: 'app.roomState.save.downloadDescription',
    description: 'Explains what downloading the room state does',
  },
  cancelLabel: {
    id: 'app.roomState.save.cancel',
    description: 'Label of the cancel button',
  },
  confirmLabel: {
    id: 'app.roomState.save.confirm',
    description: 'Label of the button that saves the room state in the session',
  },
  confirmDownloadLabel: {
    id: 'app.roomState.save.confirmDownload',
    description: 'Label of the button that downloads the room state',
  },
});

interface SaveRoomStateModalProps {
  onClose: () => void;
  // Called with true when the state is to be downloaded rather than kept in the session.
  onConfirm: (download: boolean) => void;
}

const SaveRoomStateModal: React.FC<SaveRoomStateModalProps> = ({ onClose, onConfirm }) => {
  const intl = useIntl();
  const [download, setDownload] = useState(false);

  return (
    <ModalSimple
      title={intl.formatMessage(intlMessages.title)}
      modalIsOpen
      onRequestClose={onClose}
      dataTest="roomStateSaveModal"
    >
      <Styled.Container>
        <Styled.Options role="radiogroup" aria-label={intl.formatMessage(intlMessages.title)}>
          <Styled.Option data-test="roomStateSaveSession">
            <input
              type="radio"
              name="room-state-save-target"
              checked={!download}
              onChange={() => setDownload(false)}
            />
            <Styled.OptionText>
              <Styled.OptionTitle>{intl.formatMessage(intlMessages.sessionOption)}</Styled.OptionTitle>
              <Styled.OptionDescription>
                {intl.formatMessage(intlMessages.sessionDescription)}
              </Styled.OptionDescription>
            </Styled.OptionText>
          </Styled.Option>
          <Styled.Option data-test="roomStateSaveDownload">
            <input
              type="radio"
              name="room-state-save-target"
              checked={download}
              onChange={() => setDownload(true)}
            />
            <Styled.OptionText>
              <Styled.OptionTitle>{intl.formatMessage(intlMessages.downloadOption)}</Styled.OptionTitle>
              <Styled.OptionDescription>
                {intl.formatMessage(intlMessages.downloadDescription)}
              </Styled.OptionDescription>
            </Styled.OptionText>
          </Styled.Option>
        </Styled.Options>

        <Styled.Actions>
          <Button
            label={intl.formatMessage(intlMessages.cancelLabel)}
            onClick={onClose}
            color="default"
            size="sm"
            dataTest="roomStateSaveCancel"
          />
          <Button
            label={intl.formatMessage(download ? intlMessages.confirmDownloadLabel : intlMessages.confirmLabel)}
            onClick={() => {
              onConfirm(download);
              onClose();
            }}
            color="primary"
            size="sm"
            dataTest="roomStateSaveConfirm"
          />
        </Styled.Actions>
      </Styled.Container>
    </ModalSimple>
  );
};

export default SaveRoomStateModal;
