package me.sand.minecraftaimod.client;

import me.sand.minecraftaimod.CraftOption;

import java.util.List;

public class ClientCraftUI {
    private static List<List<CraftOption>> pages = List.of();
    private static int currentPage = 0;

    public static void setPages(List<List<CraftOption>> newPages) {
        pages = newPages;
        currentPage = 0;
    }

    public static List<CraftOption> getCurrentPage() {
        if (pages.isEmpty()) return List.of();
        return pages.get(currentPage);
    }

    public static int getCurrentPageIndex() {
        return currentPage + 1;
    }

    public static int getTotalPages() {
        return pages.size();
    }

    public static void nextPage() {
        if (!pages.isEmpty())
            currentPage = (currentPage + 1) % pages.size();
    }

    public static void prevPage() {
        if (!pages.isEmpty())
            currentPage = (currentPage - 1 + pages.size()) % pages.size();
    }

    public static void resetPage(){
        currentPage = 0;
    }

    public static boolean isEmpty(){
        return pages.isEmpty();
    }

    public static String getItemDisplayName(int index){
        return getCurrentPage().get(index).displayName();
    }

    public static int getRecipeItemId(int index){
        return getCurrentPage().get(index).item_id();
    }
}
