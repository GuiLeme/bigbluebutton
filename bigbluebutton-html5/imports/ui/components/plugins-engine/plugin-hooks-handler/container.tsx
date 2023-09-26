/* eslint-disable no-undef */
// Rule applied because EvenetListener is no undefined at all times.
import React, { useEffect, useState } from 'react';
import * as PluginSdk from 'bigbluebutton-html-plugin-sdk';
import CurrentPresentationHookContainer from './use-current-presentation/container';
import LoadedUserListHookContainer from './use-loaded-user-list/container';
import CurrentUserHookContainer from './use-current-user/container';
import CustomSubscriptionHookContainer from './use-custom-subscription/container';

import { ParameterizedHookContainerProps, ParameterizedHookContainerToRender } from './types';

const hooksMap:{
  [key: string]: React.FunctionComponent
} = {
  [PluginSdk.Internal.BbbHooks.UseCurrentPresentation]: CurrentPresentationHookContainer,
  [PluginSdk.Internal.BbbHooks.UseLoadedUserList]: LoadedUserListHookContainer,
  [PluginSdk.Internal.BbbHooks.UseCurrentUser]: CurrentUserHookContainer,
};

const parameterizedHooksMap:{
  [key: string]: React.FunctionComponent<ParameterizedHookContainerProps>
} = {
  [PluginSdk.Internal.BbbHooks.UseCustomSubscription]: CustomSubscriptionHookContainer,
};

const PluginHooksHandlerContainer: React.FC = () => {
  const [
    hookUtilizationCount,
    setHookUtilizationCount,
  ] = useState(new Map<string, number>());

  const [
    parameterizedHookUtilizationCount,
    setParameterizedHookUtilizationCount,
  ] = useState(new Map<string, Map<string, number>>());

  useEffect(() => {
    const updateHookUsage = (hookName: string, delta: number, parameter?: string): void => {
      if (!hookName.includes('Parameterized')) {
        setHookUtilizationCount((mapObj) => {
          const newMap = new Map<string, number>(mapObj.entries());
          newMap.set(hookName, (mapObj.get(hookName) || 0) + delta);
          return newMap;
        });
      } else {
        setParameterizedHookUtilizationCount((mapObj) => {
          if (parameter) {
            // Create object from the parameterized hook
            const mapToBeSet = new Map<string, number>(mapObj.get(hookName)?.entries());
            mapToBeSet.set(parameter, (mapObj.get(hookName)?.get(parameter) || 0) + delta);

            // Create new parameterized map
            const newMap = new Map<string, Map<string, number>>(mapObj.entries());
            newMap.set(hookName, mapToBeSet);
            return newMap;
          } return mapObj;
        });
      }
    };

    const subscribeHandler: EventListener = (
      (event: PluginSdk.CustomEventHookWrapper<string | undefined>) => {
        updateHookUsage(event.detail.hook, 1, event.detail.parameter);
      }) as EventListener;
    const unsubscribeHandler: EventListener = (
      (event: PluginSdk.CustomEventHookWrapper<string | undefined>) => {
        updateHookUsage(event.detail.hook, -1, event.detail.parameter);
      }) as EventListener;

    window.addEventListener(PluginSdk.Internal.BbbHookEvents.Subscribe, subscribeHandler);
    window.addEventListener(PluginSdk.Internal.BbbHookEvents.Unsubscribe, unsubscribeHandler);
    return () => {
      window.removeEventListener(PluginSdk.Internal.BbbHookEvents.Subscribe, subscribeHandler);
      window.removeEventListener(PluginSdk.Internal.BbbHookEvents.Unsubscribe, unsubscribeHandler);
    };
  }, []);

  const parameterizedHooksContainerToRun: ParameterizedHookContainerToRender[] = [];
  Object.keys(parameterizedHooksMap).forEach((hookName) => {
    if (parameterizedHookUtilizationCount.get(hookName)) {
      parameterizedHookUtilizationCount.get(hookName)?.forEach((countOfPlugins, parameter) => {
        if (countOfPlugins > 0) {
          parameterizedHooksContainerToRun.push({
            componentToRender: parameterizedHooksMap[hookName],
            query: parameter,
          });
        }
      });
    }
  });

  return (
    <>
      {
        Object.keys(hooksMap)
          .filter((hookName: string) => hookUtilizationCount.get(hookName)
            && hookUtilizationCount.get(hookName)! > 0)
          .map((hookName: string) => {
            const HookComponent = hooksMap[hookName];
            return <HookComponent key={hookName} />;
          })
      }
      {
        parameterizedHooksContainerToRun.map((parameterizedHook) => {
          const HookComponent = parameterizedHook.componentToRender;
          return <HookComponent key={parameterizedHook.query} queryFromPlugin={parameterizedHook.query} />;
        })
      }
    </>
  );
};

export default PluginHooksHandlerContainer;
