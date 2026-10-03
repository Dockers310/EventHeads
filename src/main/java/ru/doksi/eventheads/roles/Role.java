package ru.doksi.eventheads.roles;

// RU: Встроенные роли EventHeads и их веса.
// EN: Built-in EventHeads roles and their hierarchy weights.

import java.util.Set;

public enum Role {
    NONE(0, Set.of()),
    ADMIN(100, Set.of("*")),
    MANAGER(80, Set.of("view","create","edit","delete","spawn","points","stats","schedule","access","give","settings","test","break","item")),
    SENIOR_MODERATOR(70, Set.of("view","edit","spawn","points","stats","test","give","break","item")),
    MODERATOR(60, Set.of("view","edit","spawn","points","stats","test","give","break")),
    SENIOR_HELPER(50, Set.of("view","points","give","test","break")),
    HELPER(40, Set.of("view","points","give","test")),
    VIEWER(10, Set.of("view"));
    private final int weight; private final Set<String> permissions;
    Role(int weight,Set<String> permissions){this.weight=weight;this.permissions=permissions;}
    public int weight(){return weight;}
    public Set<String> permissions(){return permissions;}
    public boolean allows(String permission){return permissions.contains("*")||permissions.contains(permission);}
}
