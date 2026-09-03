package io.semanticmap.platform.identity;

import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
class DevData implements ApplicationRunner {
    private final Db db;

    DevData(Db db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        db.update(
                "INSERT INTO organization(id,name) VALUES (?,?) ON CONFLICT DO NOTHING",
                TenantContext.DEV_ORG,
                "Local workspace");
        db.update(
                "INSERT INTO user_account(id,subject,display_name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                TenantContext.DEV_USER,
                "local-developer",
                "Developer");
        db.update(
                "INSERT INTO organization_member(organization_id,user_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                TenantContext.DEV_ORG,
                TenantContext.DEV_USER,
                "ORG_ADMIN");
    }
}
