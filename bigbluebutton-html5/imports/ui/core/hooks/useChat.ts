import { FetchResult } from '@apollo/client';
import createUseSubscription from './createUseSubscription';
import CHATS_SUBSCRIPTION from '../graphql/queries/chatSubscription';
import { Chat } from '../../Types/chat';

const useChatSubscription = createUseSubscription<Chat>(CHATS_SUBSCRIPTION);

const useChat = (
  fn: (c: Partial<Chat>)=> Partial<Chat>,
  chatId?: string,
): FetchResult<Array<Partial<Chat>> | Partial<Chat>> => {
  const response = useChatSubscription(fn);
  if (chatId && response.data) {
    const selectedChat = response.data.find((c: Partial<Chat>) => {
      return c.chatId === chatId;
    }) ?? null;
    return {
      ...response,
      data: selectedChat,
    } as FetchResult<Array<Chat> | Chat>;
  }
  return response;
};

export default useChat;
