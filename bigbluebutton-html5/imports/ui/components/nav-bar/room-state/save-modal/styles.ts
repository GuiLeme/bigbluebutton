import styled from 'styled-components';
import { colorGray, colorText } from '/imports/ui/stylesheets/styled-components/palette';

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-width: 20rem;
  color: ${colorText};
`;

const Options = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
`;

const Option = styled.label`
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  font-size: 0.875rem;
  cursor: pointer;
`;

const OptionText = styled.span`
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
`;

const OptionTitle = styled.span`
  font-weight: 600;
`;

const OptionDescription = styled.span`
  font-size: 0.8125rem;
  color: ${colorGray};
`;

const Actions = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
`;

export default {
  Container,
  Options,
  Option,
  OptionText,
  OptionTitle,
  OptionDescription,
  Actions,
};
