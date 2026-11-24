import styled from 'styled-components';
import {
  colorDanger,
  colorGray,
  colorGrayLighter,
  colorText,
} from '/imports/ui/stylesheets/styled-components/palette';

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-width: 20rem;
  color: ${colorText};
`;

const Label = styled.label`
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.875rem;
  font-weight: 600;
`;

const StateList = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  max-height: 12rem;
  overflow-y: auto;
`;

const StateListLegend = styled.span`
  font-size: 0.875rem;
  font-weight: 600;
`;

const StateOption = styled.label`
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  cursor: pointer;
`;

const UrlInput = styled.input`
  width: 100%;
  padding: 0.5rem;
  border: 1px solid ${colorGrayLighter};
  border-radius: 0.25rem;
  font-size: 0.875rem;
  font-weight: 400;
`;

const Divider = styled.div`
  text-align: center;
  font-size: 0.8125rem;
  color: ${colorGray};
`;

const Warning = styled.p`
  margin: 0;
  font-size: 0.875rem;
`;

const ErrorText = styled.p`
  margin: 0;
  color: ${colorDanger};
  font-size: 0.8125rem;
  font-weight: 600;
`;

const Actions = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
`;

export default {
  Container,
  Label,
  StateList,
  StateListLegend,
  StateOption,
  UrlInput,
  Divider,
  Warning,
  ErrorText,
  Actions,
};
