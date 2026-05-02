package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

@Document(collection = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String password;
    private String firstName;
    private String lastName;

    @Builder.Default
    private Set<Role> roles = Set.of(Role.USER);

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private boolean locked = false;

    /**
     * Derived from email domain on first login/registration.
     * E.g. admin@bank.com → bank.com. Used for multi-tenant KG isolation.
     */
    private String tenantId;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    public String getFullName() {
        return (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
    }
}
