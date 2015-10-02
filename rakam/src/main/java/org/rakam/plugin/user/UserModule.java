package org.rakam.plugin.user;

import com.google.auto.service.AutoService;
import com.google.common.base.Optional;
import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import org.rakam.plugin.ConditionalModule;
import org.rakam.plugin.RakamModule;
import org.rakam.plugin.SystemEventListener;
import org.rakam.plugin.UserPluginConfig;
import org.rakam.plugin.UserStorage;
import org.rakam.plugin.user.mailbox.UserMailboxStorage;
import org.rakam.report.postgresql.PostgresqlQueryExecutor;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.WebSocketService;

import javax.inject.Inject;

import static java.lang.String.format;
import static org.rakam.util.ValidationUtil.checkProject;

@AutoService(RakamModule.class)
@ConditionalModule(config="plugin.user.enabled", value = "true")
public class UserModule extends RakamModule {
    @Override
    protected void setup(Binder binder) {
        Multibinder<WebSocketService> webSocketServices = Multibinder.newSetBinder(binder, WebSocketService.class);
        webSocketServices.addBinding().to(MailBoxWebSocketService.class).in(Scopes.SINGLETON);

        Multibinder<SystemEventListener> events = Multibinder.newSetBinder(binder, SystemEventListener.class);
        events.addBinding().to(UserStorageListener.class).in(Scopes.SINGLETON);
        UserPluginConfig userPluginConfig = buildConfigObject(UserPluginConfig.class);

        Multibinder<HttpService> httpServices = Multibinder.newSetBinder(binder, HttpService.class);

        if (userPluginConfig.getStorageModule() != null) {
            httpServices.addBinding().to(UserHttpService.class).in(Scopes.SINGLETON);
        }

        if(userPluginConfig.isMailboxEnabled()) {
            httpServices.addBinding().to(UserMailboxHttpService.class).in(Scopes.SINGLETON);
        }
    }

    @Override
    public String name() {
        return "Customer Analytics Module";
    }

    @Override
    public String description() {
        return "Analyze your users";
    }

    public static class UserStorageListener implements SystemEventListener {

        private final Optional<UserStorage> storage;
        private final Optional<UserMailboxStorage> mailboxStorage;
        private final PostgresqlQueryExecutor queryExecutor;

        @Inject
        public UserStorageListener(com.google.common.base.Optional<UserStorage> storage, com.google.common.base.Optional<UserMailboxStorage> mailboxStorage, PostgresqlQueryExecutor queryExecutor) {
            this.storage = storage;
            this.mailboxStorage = mailboxStorage;
            this.queryExecutor = queryExecutor;
        }

        @Override
        public void onCreateProject(String project) {
            checkProject(project);
            // if event.store is not postgresql, schema may not exist.
            queryExecutor.executeRawQuery(format("CREATE SCHEMA IF NOT EXISTS %s", project)).getResult().join();

            if(mailboxStorage.isPresent()) {
                mailboxStorage.get().createProject(project);
            }
            if(storage.isPresent()) {
                storage.get().createProject(project);
            }
        }
    }
}
