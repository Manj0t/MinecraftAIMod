package me.sand.minecraftaimod.agent;

public enum ContainerType {
    NONE,
    CHEST,
    DOUBLE_CHEST,
    FURNACE,
    PlayerInventory,
    CRAFTING,
    MERCHANT,
    UNKNOWN;

    public int getContainerSize() {
        return switch (this) {
            case CHEST -> 27;
            case DOUBLE_CHEST -> 54;
            case FURNACE -> 3;
            default -> -1;
        };
    }
}
