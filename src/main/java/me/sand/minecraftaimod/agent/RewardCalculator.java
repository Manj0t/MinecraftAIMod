package me.sand.minecraftaimod.agent;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RewardCalculator {

    private final ServerPlayerEntity agent;

    private Vec3d lastPos = null;
    private double agentPrevHealth = 0;
    private Vec3d goalPos = null;
    private boolean goalReached = false;

    private final int REGION = 1;
    private final Set<RegionKey> visitedRegions = new HashSet<>();

    record RegionKey(int x, int z) {}

    public RewardCalculator(ServerPlayerEntity agent) {
        this.agent = agent;
    }

    public void setGoal(Vec3d goal) {
        this.goalPos = goal;
    }

    public boolean claimGoalReached() {
        if (goalReached) return false;
        goalReached = true;
        return true;
    }

    public void reset() {
        lastPos = null;
        agentPrevHealth = 0;
        visitedRegions.clear();
        goalReached = false;
    }

    public double getReward(List<Float> actions) {
        try {
            Vec3d currentPos = new Vec3d(agent.getX(), agent.getY(), agent.getZ());

            if (lastPos == null) {
                lastPos = currentPos;
                agentPrevHealth = agent.getHealth();
                return 0.0;
            }

            double reward = -0.01;

            int regionX = (int) Math.floor(currentPos.x / REGION);
            int regionZ = (int) Math.floor(currentPos.z / REGION);
            RegionKey currentRegion = new RegionKey(regionX, regionZ);

            if (!visitedRegions.contains(currentRegion)) {
                visitedRegions.add(currentRegion);
                reward += 2.0;
            }

            if (agent.isInLava() || agent.isOnFire()) {
                reward -= 5.0;
            }

            lastPos = currentPos;
            agentPrevHealth = agent.getHealth();
            return reward;

        } catch (Exception e) {
            return 0.0;
        }
    }
}
