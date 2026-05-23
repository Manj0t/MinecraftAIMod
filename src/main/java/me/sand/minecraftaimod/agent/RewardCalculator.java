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

    private final int REGION = 1;
    private final Set<RegionKey> visitedRegions = new HashSet<>();

    record RegionKey(int x, int z) {}

    public RewardCalculator(ServerPlayerEntity agent) {
        this.agent = agent;
    }

    public void reset() {
        lastPos = null;
        agentPrevHealth = 0;
        visitedRegions.clear();
    }

    public double getReward(List<Float> actions) {
        try {
            Vec3d currentPos = new Vec3d(agent.getX(), agent.getY(), agent.getZ());

            if (lastPos == null) {
                lastPos = currentPos;
                agentPrevHealth = agent.getHealth();
                return 0.0;
            }

            double reward = 0.0;

            // Movement reward
            double dx = currentPos.x - lastPos.x;
            double dz = currentPos.z - lastPos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist > 0.05) {
                reward += dist * 0.5;
            }

            // New area discovery
            int regionX = (int) Math.floor(currentPos.x / REGION);
            int regionZ = (int) Math.floor(currentPos.z / REGION);
            RegionKey currentRegion = new RegionKey(regionX, regionZ);

            if (!visitedRegions.contains(currentRegion)) {
                visitedRegions.add(currentRegion);
                reward += 2.0;
            }

            // Penalty for standing still
            if (dist < 0.05) {
                reward -= 0.1;
            }

            // Penalty for collision while trying to move
            boolean isTryingToMove = actions.get(1) <= 4;
            if (agent.horizontalCollision && isTryingToMove) {
                reward -= 0.5;
            }

            // Penalty for unnecessary jumping
            boolean jumped = (actions.get(1) == 2 || actions.get(1) == 3);
            if (jumped) {
                reward -= 0.1;
            }

            // Penalty for being in air
            if (!agent.isOnGround() && !agent.isInLava()) {
                reward -= 0.05;
            }

            // Big penalties for danger
            if (agent.isInLava() || agent.isOnFire()) {
                reward -= 5.0;
            }

            // Damage penalty
            if (agent.getHealth() < agentPrevHealth) {
                double damage = agentPrevHealth - agent.getHealth();
                reward -= damage * 2.0;
            }

            lastPos = currentPos;
            agentPrevHealth = agent.getHealth();
            return reward;

        } catch (Exception e) {
            return 0.0;
        }
    }
}
