package org.apache.dubbo.demo.provider.filter;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;

@Activate(group = "provider", value = "YourProjectKey", order = -1)
public class YourProjectFilter extends ListenableFilter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        addListener(invocation, new Listener() {
            @Override
            public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {

            }

            @Override
            public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

            }
        });

        return invoker.invoke(invocation);
    }

}
