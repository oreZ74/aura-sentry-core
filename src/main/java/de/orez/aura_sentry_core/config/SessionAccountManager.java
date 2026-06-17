package de.orez.aura_sentry_core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import de.orez.aura_sentry_core.persistence.entity.UserEntity;
import jakarta.servlet.http.HttpSession;

@Component
public class SessionAccountManager {

    private static final String ACTIVE_ACCOUNTS_KEY = "activeAccounts";

    public void addAccount(HttpSession session, UserEntity user) {
        List<ActiveAccount> accounts = getAccounts(session);
        boolean exists = accounts.stream().anyMatch(a -> a.email().equalsIgnoreCase(user.getEmail()));
        if (!exists) {
            ActiveAccount account = new ActiveAccount(
                    user.getFullName(),
                    user.getEmail(),
                    user.getInitials());
            List<ActiveAccount> mutable = new ArrayList<>(accounts);
            mutable.add(account);
            session.setAttribute(ACTIVE_ACCOUNTS_KEY, Collections.unmodifiableList(mutable));
        }
    }

    public void removeAccount(HttpSession session, String email) {
        List<ActiveAccount> accounts = getAccounts(session);
        List<ActiveAccount> filtered = accounts.stream()
                .filter(a -> !a.email().equalsIgnoreCase(email))
                .toList();
        session.setAttribute(ACTIVE_ACCOUNTS_KEY, filtered.isEmpty() ? null : Collections.unmodifiableList(filtered));
    }

    public List<ActiveAccount> getAccounts(HttpSession session) {
        @SuppressWarnings("unchecked")
        List<ActiveAccount> accounts = (List<ActiveAccount>) session.getAttribute(ACTIVE_ACCOUNTS_KEY);
        return accounts != null ? accounts : Collections.emptyList();
    }
}
