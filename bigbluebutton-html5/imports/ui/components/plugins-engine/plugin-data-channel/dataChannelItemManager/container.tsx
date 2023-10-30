import React, { useEffect } from 'react';
import { useMutation, useSubscription } from '@apollo/client';
import * as PluginSdk from 'bigbluebutton-html-plugin-sdk';

import { PLUGIN_DATA_CHANNEL_DISPATCH_QUERY, PLUGIN_DATA_CHANNEL_FETCH_QUERY } from '../../queries';

export interface DataChannelItemManagerProps {
  pluginName: string;
  channelName: string;
  pluginApi: PluginSdk.PluginApi
}

export interface MutationVariables {
  pluginName: string,
  dataChannel: string,
  payloadJson: string,
  toRoles: PluginSdk.Role[],
  toUserIds: string[],
}

export const DataChannelItemManager: React.ElementType<DataChannelItemManagerProps> = (
  props: DataChannelItemManagerProps,
) => {
  const {
    pluginName,
    channelName,
    pluginApi,
  } = props;
  const pluginIdentifier = PluginSdk.createChannelIdentifier(channelName, pluginName);

  const dataChannelIdentifier = PluginSdk.createChannelIdentifier(channelName, pluginName);
  const [dispatchPluginDataChannelMessage] = useMutation(PLUGIN_DATA_CHANNEL_DISPATCH_QUERY);

  const data = useSubscription(PLUGIN_DATA_CHANNEL_FETCH_QUERY, {
    variables: {
      pluginName,
      channelName,
    },
  });

  const useDataChannelHandlerFunction = ((msg: object, objectsTo?: PluginSdk.ObjectTo[]) => {
    const argumentsOfDispatcher = {
      variables: {
        pluginName,
        dataChannel: channelName,
        payloadJson: JSON.stringify(msg),
        toRoles: [],
        toUserIds: [],
      } as MutationVariables,
    };

    if (objectsTo) {
      const rolesTo: PluginSdk.Role[] = objectsTo.filter((
        object: PluginSdk.ObjectTo,
      ) => 'role' in object).map(
        (object: PluginSdk.ObjectTo) => {
          const toRole = object as PluginSdk.ToRole;
          return toRole.role;
        },
      );
      const usersTo = objectsTo.filter((
        object: PluginSdk.ObjectTo,
      ) => 'userId' in object).map(
        (object: PluginSdk.ObjectTo) => {
          const toUserId = object as PluginSdk.ToUserId;
          return toUserId.userId;
        },
      );
      if (rolesTo.length > 0) argumentsOfDispatcher.variables.toRoles = rolesTo;
      if (usersTo.length > 0) argumentsOfDispatcher.variables.toUserIds = usersTo;
    }
    dispatchPluginDataChannelMessage(argumentsOfDispatcher);
  }) as PluginSdk.DispatcherFunction;

  pluginApi.mapOfDispatchers[pluginIdentifier] = useDataChannelHandlerFunction;
  window.dispatchEvent(new Event(`${pluginIdentifier}::dispatcherFunction`));

  useEffect(() => {
    window.dispatchEvent(
      new CustomEvent(dataChannelIdentifier, {
        detail: { hook: PluginSdk.Internal.BbbDataChannel.UseDataChannel, data },
      }),
    );
  }, [data]);
  return null;
};
