/**
 *  Copyright (C) 2002-2017   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.freecol.server.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.sf.freecol.common.i18n.Messages;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.AbstractGoods;
import net.sf.freecol.common.model.BuildQueue;
import net.sf.freecol.common.model.BuildableType;
import net.sf.freecol.common.model.Building;
import net.sf.freecol.common.model.BuildingType;
import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.ColonyTile;
import net.sf.freecol.common.model.Consumer;
import net.sf.freecol.common.model.ExportData;
import net.sf.freecol.common.model.Game;
import net.sf.freecol.common.model.Goods;
import net.sf.freecol.common.model.GoodsContainer;
import net.sf.freecol.common.model.GoodsType;
import net.sf.freecol.common.model.Market;
import net.sf.freecol.common.model.ModelMessage;
import net.sf.freecol.common.model.ModelMessage.MessageType;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.ProductionInfo;
import net.sf.freecol.common.model.Role;
import net.sf.freecol.common.model.Specification;
import net.sf.freecol.common.model.StringTemplate;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.TypeCountMap;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.model.UnitChangeType;
import net.sf.freecol.common.model.UnitType;
import net.sf.freecol.common.model.WorkLocation;
import net.sf.freecol.common.networking.ChangeSet;
import net.sf.freecol.common.networking.ChangeSet.See;
import net.sf.freecol.common.util.LogBuilder;
import static net.sf.freecol.common.util.CollectionUtils.*;
import static net.sf.freecol.common.util.RandomUtils.*;
import net.sf.freecol.server.model.ServerPlayer;


/**
 * The server version of a colony.
 */
public class ServerColony extends Colony implements ServerModelObject {

    private static final Logger logger = Logger.getLogger(ServerColony.class.getName());


    /**
     * Trivial constructor required for all ServerModelObjects.
     *
     * @param game The {@code Game} in which this object belongs.
     * @param id The object identifier.
     */
    public ServerColony(Game game, String id) {
        super(game, id);
    }

    /**
     * Creates a new ServerColony.
     *
     * @param game The {@code Game} in which this object belongs.
     * @param owner The {@code Player} owning this {@code Colony}.
     * @param name The name of the new {@code Colony}.
     * @param tile The location of the {@code Colony}.
     */
    public ServerColony(Game game, Player owner, String name, Tile tile) {
        super(game, owner, name, tile);
        Specification spec = getSpecification();

        setGoodsContainer(new GoodsContainer(game, this));
        sonsOfLiberty = 0;
        oldSonsOfLiberty = 0;
        established = game.getTurn();

        ColonyTile colonyTile = new ServerColonyTile(game, this, tile);
        colonyTiles.add(colonyTile);
        for (Tile t : tile.getSurroundingTiles(getRadius())) {
            colonyTiles.add(new ServerColonyTile(game, this, t));
        }

        Building building;
        List<BuildingType> buildingTypes = spec.getBuildingTypeList();
        for (BuildingType buildingType : transform(buildingTypes, bt ->
                bt.isAutomaticBuild() || isAutomaticBuild(bt))) {
            addBuilding(new ServerBuilding(getGame(), this, buildingType));
        }
        // Set up default production queues.  Do this after calling
        // addBuilding because that will check build queue integrity,
        // and these might fail the population check.
        // FIXME: express this in the spec somehow.
        if (isLandLocked()) {
            buildQueue.add(spec.getBuildingType("model.building.warehouse"));
        } else {
            buildQueue.add(spec.getBuildingType("model.building.docks"));
            addPortAbility();
        }
        for (UnitType unitType : transform(spec.getUnitTypesWithAbility(Ability.BORN_IN_COLONY),
                                           UnitType::needsGoodsToBuild)) {
            populationQueue.add(unitType);
        }
    }

    /**
     * Is a goods type needed for a buildable that this colony could
     * be building.
     *
     * @param goodsType The {@code GoodsType} to check.
     * @return True if the goods could be used to build something.
     */
    private boolean neededForBuildableType(GoodsType goodsType) {
        final Specification spec = getSpecification();
        List<BuildableType> buildables = new ArrayList<>();
        buildables.addAll(spec.getBuildingTypeList());
        buildables.addAll(spec.getUnitTypesWithoutAbility(Ability.PERSON));
        return any(buildables, bt -> canBuild(bt)
            && any(bt.getRequiredGoods(), AbstractGoods.matches(goodsType)));
    }

    /**
     * Build a unit from a build queue.
     *
     * @param buildQueue The {@code BuildQueue} to find the unit in.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     * @return The unit that was built.
     */
    private Unit csBuildUnit(BuildQueue<? extends BuildableType> buildQueue,
                             Random random, ChangeSet cs) {
        UnitType type = (UnitType)buildQueue.getCurrentlyBuilding();
        Unit unit = new ServerUnit(getGame(), getTile(), owner,
                                   type);//-vis: safe, within colony
        if (unit.hasAbility(Ability.BORN_IN_COLONY)) {
            cs.addMessage(owner,
                          new ModelMessage(MessageType.UNIT_ADDED,
                                           "model.colony.newColonist",
                                           this, unit)
                              .addName("%colony%", getName()));
        } else {
            unit.setName(owner.getNameForUnit(type, random));
            cs.addMessage(owner,
                          new ModelMessage(MessageType.UNIT_ADDED,
                                           "model.colony.unitReady",
                                           this, unit)
                              .addName("%colony%", getName())
                              .addStringTemplate("%unit%", unit.getLabel()));
        }

        logger.info("New unit in " + getName() + ": " + type.getSuffix());
        return unit;
    }

    /**
     * Eject units to any available work location.
     *
     * Called on building type changes, see below and
     * @see ServerPlayer#csDamageBuilding
     *
     * -til: Might change the visible colony size.
     *
     * @param workLocation The {@code WorkLocation} to eject from.
     * @param units A list of {@code Unit}s to eject.
     * @return True if units were ejected.
     */
    public boolean ejectUnits(WorkLocation workLocation, List<Unit> units) {
        if (units == null || units.isEmpty()) return false;
        unit: for (Unit u : units) {
            for (WorkLocation wl : transform(getAvailableWorkLocations(),
                                             w -> w != workLocation && w.canAdd(u))) {
                u.setLocation(wl);//-vis: safe/colony
                continue unit;
            }
            u.setLocation(getTile());//-vis: safe/colony
        }
        if (getOwner().isAI()) {
            firePropertyChange(REARRANGE_COLONY, true, false);
        }
        return true;
    }

    /**
     * Builds a building from a build queue.
     *
     * @param buildQueue The {@code BuildQueue} to build from.
     * @param cs A {@code ChangeSet} to update.
     * @return True if the build succeeded.
     */
    private boolean csBuildBuilding(BuildQueue<? extends BuildableType> buildQueue,
                                    ChangeSet cs) {
        BuildingType type = (BuildingType) buildQueue.getCurrentlyBuilding();
        Tile copied = getTile().getTileToCache();
        BuildingType from = type.getUpgradesFrom();
        boolean success;
        if (from == null) {
            success = buildBuilding(new ServerBuilding(getGame(), this, type));//-til
        } else {
            Building building = getBuilding(from);
            List<Unit> eject = building.upgrade();//-til
            success = eject != null;
            if (success) {
                ejectUnits(building, eject);//-til
                if (!eject.isEmpty()) getTile().cacheUnseen(copied);//+til
            } else {
                cs.addMessage(owner, getUnbuildableMessage(type));
            }
        }
        if (success) {
            cs.addMessage(owner,
                new ModelMessage(MessageType.BUILDING_COMPLETED,
                                 "model.colony.buildingReady", this)
                    .addName("%colony%", getName())
                    .addNamed("%building%", type));
            if (owner.isAI()) {
                firePropertyChange(REARRANGE_COLONY, true, false);
            }
            logger.info("New building in " + getName()
                + ": " + type.getSuffix());
        }
        return success;
    }

    /**
     * Removes a buildable from a build queue, and updates the queue so that
     * a valid buildable is now being built if possible.
     *
     * @param queue The {@code BuildQueue} to update.
     * @param cs A {@code ChangeSet} to update.
     * @return The next buildable that can be built, or null if nothing.
     */
    private BuildableType csNextBuildable(BuildQueue<? extends BuildableType> queue,
                                          ChangeSet cs) {
        Specification spec = getSpecification();
        ServerPlayer owner = (ServerPlayer) getOwner();
        BuildableType buildable;
        boolean invalidate = false;

        while ((buildable = queue.getCurrentlyBuilding()) != null) {
            switch (getNoBuildReason(buildable, null)) {
            case LIMIT_EXCEEDED:
                // Expected when a player builds its last available wagon
                // and there is nothing else in the build queue.
                break;
            case NONE:
                return buildable;
            case NOT_BUILDING:
                for (GoodsType goodsType : transform(spec.getGoodsTypeList(),
                        gt -> (gt.isBuildingMaterial()
                            && !gt.isStorable()
                            && getTotalProductionOf(gt) > 0))) {
                    // Production is idle
                    cs.addMessage(owner,
                        new ModelMessage(MessageType.WARNING,
                                         "model.colony.cannotBuild", this)
                            .addName("%colony%", getName()));
                }
                return null;
                
            case POPULATION_TOO_SMALL:
                cs.addMessage(owner,
                    new ModelMessage(MessageType.WARNING,
                                     "model.colony.buildNeedPop",
                                     this)
                        .addName("%colony%", getName())
                        .addNamed("%building%", buildable));
                break;
            default: // Are there other warnings to send?
                logger.warning("Unexpected build failure at " + getName()
                    + " for " + buildable
                    + ": " + getNoBuildReason(buildable, null));
                cs.addMessage(owner,
                              getUnbuildableMessage(buildable));
                break;
            }
            queue.remove(0);
            invalidate = true;
        }
        if (invalidate) invalidateCache();
        return null;
    }

    /**
     * Evict the users from a tile used by this colony, due to military
     * action from another unit.
     *
     * @param enemyUnit The {@code Unit} that has moved in.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csEvictUsers(Unit enemyUnit, ChangeSet cs) {
        ServerPlayer serverPlayer = (ServerPlayer)getOwner();
        Tile tile = enemyUnit.getTile();
        ServerColonyTile ct = (ServerColonyTile)getColonyTile(tile);
        if (ct == null) return;
        Tile colonyTile = ct.getColony().getTile();
        Tile copied = colonyTile.getTileToCache();
        if (!ejectUnits(ct, ct.getUnitList())) return;//-til
        colonyTile.cacheUnseen(copied);//+til
        cs.addMessage(serverPlayer,
            new ModelMessage(MessageType.WARNING,
                             "model.colony.workersEvicted",
                             this, this)
                .addName("%colony%", getName())
                .addStringTemplate("%location%", tile.getLocationLabel())
                .addStringTemplate("%enemyUnit%", enemyUnit.getLabel()));
        cs.add(See.only(serverPlayer), ct);
        cs.add(See.perhaps(), getTile()); // Colony size might have changed
    }

    /**
     * Change the owner of this colony.
     *
     * -vis: Owner and new owner
     *
     * @param newOwner The new owning {@code ServerPlayer}.
     * @param reassign If true, reassign the colony tiles.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csChangeOwner(ServerPlayer newOwner, boolean reassign,
                              ChangeSet cs) {
        final ServerPlayer oldOwner = (ServerPlayer)getOwner();
        final Tile tile = getTile();
        final Set<Tile> owned = getOwnedTiles();
        final Set<Tile> unseen
            = transform(tile.getSurroundingTiles(1, getLineOfSight()),
                        t -> !newOwner.hasExplored(t) || !newOwner.canSee(t),
                        Function.identity(), Collectors.toSet());

        for (Tile t : owned) t.cacheUnseen(newOwner);//+til
        
        if (reassign) {
            // Allow other neighbouring settlements a chance to claim
            // this colony's tiles except for the center.
            owned.remove(tile);
            oldOwner.reassignTiles(owned, this);//-til

            // Make sure units are ejected when a tile is claimed.
            for (Tile t : transform(owned,
                    t2 -> t2 != tile && t2.getOwningSettlement() != this)) {
                ColonyTile ct = getColonyTile(t);
                ejectUnits(ct, ct.getUnitList());
            }
            owned.add(tile);
        }
        
        changeOwner(newOwner);//-vis(both),-til

        String change = (newOwner.isUndead()) ? UnitChangeType.UNDEAD
            : UnitChangeType.CAPTURE;
        List<Unit> units = getAllUnitsList();
        for (Unit u : units) {//-vis(both)
            oldOwner.csChangeOwner(u, newOwner, change, null, cs);
        }
        cs.addRemoves(See.only(oldOwner), this, units);

        // Disable all exports
        for (ExportData exportDatum : exportData.values()) {
            exportDatum.setExported(false);
        }

        // Clear the build queue
        buildQueue.clear();

        // Add free buildings from new owner
        for (BuildingType bt : newOwner.getFreeBuildingTypes()) {
            csFreeBuilding(bt, cs);
        }

        // Changing the owner might alter bonuses applied by founding fathers:
        updateSoL();
        updateProductionBonus();

        // Always update the new owner
        unseen.addAll(getOwnedTiles());
        cs.add(See.only(newOwner), unseen);

        // Always update the old owner, and perhaps others.
        cs.add(See.perhaps().always(oldOwner).except(newOwner), owned);
    }

    /**
     * Add a free building to this colony.
     *
     * Triggered by election of laSalle and colony capture by a player
     * with laSalle.
     *
     * @param type The {@code BuildingType} to add.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csFreeBuilding(BuildingType type, ChangeSet cs) {
        if (canBuild(type)) {
            final ServerPlayer owner = (ServerPlayer)getOwner();
            buildBuilding(new ServerBuilding(getGame(), this, type));//-til
            checkBuildQueueIntegrity(true);
            cs.add(See.only(owner), this);
            if (owner.isAI()) {
                firePropertyChange(Colony.REARRANGE_COLONY, true, false);
            }
        }
    }

    /**
     * Build a new building in this colony.
     *
     * @param building The {@code Building} to build.
     * @return True if the building was built.
     */
    public boolean buildBuilding(Building building) {
        Tile copied = (building.getType().isDefenceType())
            ? getTile().getTileToCache() : null;
        if (!addBuilding(building)) return false;
        getTile().cacheUnseen(copied);
        invalidateCache();
        return true;
    }

    /**
     * Equip a unit for a specific role.
     *
     * @param unit The {@code Unit} to equip.
     * @param role The {@code Role} to equip for.
     * @param roleCount The role count.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     * @return True if the equipping succeeds.
     */
    public boolean csEquipForRole(Unit unit, Role role, int roleCount,
                                  Random random, ChangeSet cs) {
        boolean ret = equipForRole(unit, role, roleCount);

        if (ret) {
            if (unit.isOnCarrier()) unit.setMovesLeft(0);
            Tile tile = getTile();
            tile.cacheUnseen();//+til
            unit.setLocation(tile);
            cs.add(See.perhaps(), tile);
        }
        return ret;
    }

    /**
     * Add a new convert to this colony.
     *
     * @param brave The convert {@code Unit}.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csAddConvert(Unit brave, ChangeSet cs) {
        if (brave == null) return;
        ServerPlayer newOwner = (ServerPlayer)getOwner();
        ServerPlayer oldOwner = (ServerPlayer)brave.getOwner();
        if (oldOwner.csChangeOwner(brave, newOwner, UnitChangeType.CONVERSION, 
                                   getTile(), cs)) { //-vis(other)
            brave.changeRole(getSpecification().getDefaultRole(), 0);
            for (Goods g : brave.getGoods()) brave.removeGoods(g);
            brave.setMovesLeft(0);
            brave.setState(Unit.UnitState.ACTIVE);
            cs.addDisappear(newOwner, tile, brave);
            cs.add(See.only(newOwner), getTile());
            StringTemplate nation = oldOwner.getNationLabel();
            cs.addMessage(newOwner,
                new ModelMessage(MessageType.UNIT_ADDED,
                                "model.colony.newConvert", brave)
                    .addStringTemplate("%nation%", nation)
                    .addName("%colony%", getName()));
            newOwner.invalidateCanSeeTiles();//+vis(other)
            logger.fine("Convert at " + getName() + " for " + getName());
        }
    }

    /**
     * Destroy an existing building in this colony.
     *
     * @param building The {@code Building} to destroy.
     * @return True if the building was destroyed.
     */
    public boolean destroyBuilding(Building building) {
        Tile copied = (building.getType().isDefenceType())
            ? getTile().getTileToCache() : null;
        if (!removeBuilding(building)) return false;
        getTile().cacheUnseen(copied);
        invalidateCache();
        checkBuildQueueIntegrity(true);
        return true;
    }


    // Implement ServerModelObject

    /**
     * New turn for this colony.
     * Try to find out if the colony is going to survive (last colonist does
     * not starve) before generating lots of production-related messages.
     *
     * @param random A {@code Random} number source.
     * @param lb A {@code LogBuilder} to log to.
     * @param cs A {@code ChangeSet} to update.
     */
    @Override
    public void csNewTurn(Random random, LogBuilder lb, ChangeSet cs) {
        lb.add("COLONY ", this);
        final Specification spec = getSpecification();
        final ServerPlayer owner = (ServerPlayer) getOwner();
        BuildQueue<?>[] queues = new BuildQueue<?>[] { buildQueue,
                 populationQueue };
        final Tile tile = getTile();

        // The AI is prone to removing all units from a colony.
        // Clean up such cases, to avoid other players seeing the
        // nonsensical 0-unit colony.
        if (getUnitCount() <= 0) {
            lb.add(" 0-unit DISPOSING, ");
            owner.csDisposeSettlement(this, cs);
            return;
        }

        boolean tileDirty = false;
        boolean newUnitBorn = false;
        GoodsContainer container = getGoodsContainer();
        container.saveState();

        // Check for learning by experience
        for (WorkLocation workLocation : getCurrentWorkLocationsList()) {
            ((ServerModelObject)workLocation).csNewTurn(random, lb, cs);
            ProductionInfo productionInfo = getProductionInfo(workLocation);
            if (productionInfo == null) continue;
            if (!workLocation.isEmpty()) {
                for (AbstractGoods goods : productionInfo.getProduction()) {
                    UnitType expert = spec.getExpertForProducing(goods.getType());
                    int experience = goods.getAmount() / workLocation.getUnitCount();
                    for (Unit unit : transform(workLocation.getUnits(),
                            u -> u.getExperienceType() == goods.getType()
                            && u.getUnitChange(UnitChangeType.EXPERIENCE,
                                               expert) != null)) {
                        unit.setExperience(unit.getExperience() + experience);
                        cs.addPartial(See.only(owner), unit,
                            "experience", String.valueOf(unit.getExperience()));
                    }
                }
            }
            if (workLocation instanceof ServerBuilding) {
                // FIXME: generalize to other WorkLocations?
                ((ServerBuilding)workLocation).csCheckMissingInput(productionInfo, cs, true);
            }
        }

        // Check the build queues and build new stuff.  If a queue
        // does a build add it to the built list, so that we can
        // remove the item built from it *after* applying the
        // production changes.
        List<BuildQueue<? extends BuildableType>> built = new ArrayList<>();
        for (BuildQueue<?> queue : queues) {
            ProductionInfo info = getProductionInfo(queue);
            if (info == null) continue;
            if (info.getConsumption().isEmpty()) {
                BuildableType build = queue.getCurrentlyBuilding();
                if (build != null) {
                    AbstractGoods needed = new AbstractGoods();
                    int complete = getTurnsToComplete(build, needed);
                    // Warn if about to fail, or if no useful progress
                    // towards completion is possible.
                    if (complete == -2 || complete == -1) {
                        cs.addMessage(owner,
                            new ModelMessage(MessageType.MISSING_GOODS,
                                             "model.colony.buildableNeedsGoods",
                                             this, build)
                                .addName("%colony%", getName())
                                .addNamed("%buildable%", build)
                                .addAmount("%amount%", needed.getAmount())
                                .addNamed("%goodsType%", needed.getType()));
                    }
                }
            } else {
                // Ready to build something.  FIXME: OO!
                BuildableType buildable = csNextBuildable(queue, cs);
                if (buildable == null) {
                    ; // It was invalid, ignore.
                } else if (buildable instanceof UnitType) {
                    Unit newUnit = csBuildUnit(queue, random, cs);
                    if (newUnit.hasAbility(Ability.BORN_IN_COLONY)) {
                        newUnitBorn = true;
                    }
                    built.add(queue);
                } else if (buildable instanceof BuildingType) {
                    int unitCount = getUnitCount();
                    if (csBuildBuilding(queue, cs)) {
                        built.add(queue);
                        // Visible change if building changed the
                        // stockade level or ejected units.
                        tileDirty = ((BuildingType)buildable).isDefenceType()
                            || unitCount != getUnitCount();
                    }
                } else {
                    throw new IllegalStateException("Bogus buildable: "
                                                    + buildable);
                }
            }
        }

        // Apply the accumulated production changes.
        // Beware that if you try to build something requiring hammers
        // and tools, as soon as one is updated in the colony the
        // current production cache is invalidated, and the
        // recalculated one will see the build as incomplete due to
        // missing the goods just updated.
        // Hence the need for a copy of the current production map.
        TypeCountMap<GoodsType> productionMap = getProductionMap();
        for (GoodsType goodsType : productionMap.keySet()) {
            int net = productionMap.getCount(goodsType);
            int stored = getGoodsCount(goodsType);
            if (net + stored <= 0) {
                removeGoods(goodsType, stored);
            } else {
                addGoods(goodsType, net);
            }

            // Handle the food situation
            if (goodsType == spec.getPrimaryFoodType()) {
                // Check for famine when total primary food goes negative.
                if (net + stored < 0) {
                    if (getUnitCount() > 1) {
                        Unit victim = getRandomMember(logger, "Starver",
                                                      getUnits(), random);
                        ((ServerUnit)victim).csRemove(See.only(owner), null,
                            cs);//-vis: safe, all within colony

                        cs.addMessage(owner,
                            new ModelMessage(MessageType.UNIT_LOST,
                                             "model.colony.colonistStarved",
                                             this)
                                .addName("%colony%", getName()));
                    } else { // Its dead, Jim.
                        cs.addMessage(owner,
                            new ModelMessage(MessageType.UNIT_LOST,
                                             "model.colony.colonyStarved",
                                             this)
                                .addName("%colony%", getName()));
                        owner.csDisposeSettlement(this, cs);
                        return;
                    }
                } else if (net < 0) {
                    int turns = stored / -net;
                    if (turns <= Colony.FAMINE_TURNS && !newUnitBorn) {
                        cs.addMessage(owner,
                            new ModelMessage(MessageType.WARNING,
                                             "model.colony.famineFeared",
                                             this)
                                .addName("%colony%", getName())
                                .addAmount("%number%", turns));
                        lb.add(" famine in ", turns,
                               " food=", stored, " production=", net);
                    }
                }
            }
        }
        invalidateCache();

        // Now that the goods have been updated it is safe to remove the
        // built item from its build queue.
        if (!built.isEmpty()) {
            for (BuildQueue<? extends BuildableType> queue : built) {
                switch (queue.getCompletionAction()) {
                case SHUFFLE:
                    if (queue.size() > 1) {
                        randomShuffle(logger, "Build queue",
                                      queue.getValues(), random);
                    }
                    break;
                case REMOVE_EXCEPT_LAST:
                    if (queue.size() == 1
                        && queue.getCurrentlyBuilding() instanceof UnitType) {
                        // Repeat last unit
                        break;
                    }
                    // Fall through
                case REMOVE:
                default:
                    queue.remove(0);
                    break;
                }
                csNextBuildable(queue, cs);
            }
            tileDirty = true;
        }

        // Export goods if custom house is built.
        // Do not flush price changes yet, as any price change may change
        // yet again in csYearlyGoodsAdjust.
        if (hasAbility(Ability.EXPORT)) {
            LogBuilder lb2 = new LogBuilder(64);
            lb2.add(" ");
            lb2.mark();
            for (Goods goods : getCompactGoods()) {
                GoodsType type = goods.getType();
                ExportData data = getExportData(type);
                if (!data.getExported()
                    || !owner.canTrade(goods.getType(), Market.Access.CUSTOM_HOUSE)) continue;
                int amount = goods.getAmount() - data.getExportLevel();
                if (amount <= 0) continue;
                int oldGold = owner.getGold();
                int marketAmount = owner.sell(container, type, amount);
                if (marketAmount > 0) {
                    owner.addExtraTrade(new AbstractGoods(type, marketAmount));
                }
                StringTemplate st = StringTemplate.template("model.colony.customs.saleData")
                    .addAmount("%amount%", amount)
                    .addNamed("%goods%", type)
                    .addAmount("%gold%", (owner.getGold() - oldGold));
                lb2.add(Messages.message(st), ", ");
            }
            if (lb2.grew()) {
                lb2.shrink(", ");
                cs.addMessage(owner,
                    new ModelMessage(MessageType.GOODS_MOVEMENT,
                                     "model.colony.customs.sale", this)
                        .addName("%colony%", getName())
                        .addName("%data%", lb2.toString()));
                cs.addPartial(See.only(owner), owner,
                    "gold", String.valueOf(owner.getGold()));
                lb.add(lb2.toString());
            }
        }

        // Throw away goods there is no room for, and warn about
        // levels that will be exceeded next turn
        final int limit = getWarehouseCapacity();
        final int adjustment = limit / GoodsContainer.CARGO_SIZE;
        for (Goods goods : transform(getCompactGoods(),
                                     AbstractGoods::isStorable)) {
            final GoodsType type = goods.getType();
            final ExportData exportData = getExportData(type);
            final int low = exportData.getLowLevel() * adjustment;
            final int high = exportData.getHighLevel() * adjustment;
            int amount = goods.getAmount();
            int oldAmount = container.getOldGoodsCount(type);

            if (amount < low && oldAmount >= low
                && !(type == spec.getPrimaryFoodType() && newUnitBorn)) {
                cs.addMessage(owner,
                    new ModelMessage(MessageType.WAREHOUSE_CAPACITY,
                                     "model.colony.warehouseEmpty",
                                     this, type)
                        .addNamed("%goods%", type)
                        .addAmount("%level%", low)
                        .addName("%colony%", getName()));
                continue;
            }
            if (type.limitIgnored()) continue;
            String messageId = null;
            int waste = 0;
            if (amount > limit) {
                // limit has been exceeded
                waste = amount - limit;
                container.removeGoods(type, waste);
                messageId = "model.colony.warehouseWaste";
            } else if (amount == limit && oldAmount < limit) {
                // limit has been reached during this turn
                messageId = "model.colony.warehouseOverfull";
            } else if (amount > high && oldAmount <= high) {
                // high-water-mark has been reached this turn
                messageId = "model.colony.warehouseFull";
            }
            if (messageId != null) {
                cs.addMessage(owner,
                    new ModelMessage(MessageType.WAREHOUSE_CAPACITY,
                                     messageId, this, type)
                        .addNamed("%goods%", type)
                        .addAmount("%waste%", waste)
                        .addAmount("%level%", high)
                        .addName("%colony%", getName()));
            }

            // No problem this turn, but what about the next?
            if (!(exportData.getExported()
                  && hasAbility(Ability.EXPORT)
                  && owner.canTrade(type, Market.Access.CUSTOM_HOUSE))
                && amount <= limit) {
                int loss = amount + getNetProductionOf(type) - limit;
                if (loss > 0) {
                    cs.addMessage(owner,
                        new ModelMessage(MessageType.WAREHOUSE_CAPACITY,
                                         "model.colony.warehouseSoonFull",
                                         this, type)
                            .addNamed("%goods%", goods)
                            .addName("%colony%", getName())
                            .addAmount("%amount%", loss));
                }
            }
        }

        // Check for free buildings
        for (BuildingType buildingType : transform(spec.getBuildingTypeList(),
                bt -> isAutomaticBuild(bt))) {
            buildBuilding(new ServerBuilding(getGame(), this,
                                             buildingType));//-til
        }
        checkBuildQueueIntegrity(true);

        // If a build queue is empty, check that we are not producing
        // any goods types useful for BuildableTypes, except if that
        // type is the input to some other form of production.  (Note:
        // isBuildingMaterial is also true for goods used to produce
        // role-equipment, hence neededForBuildableType).  Such
        // production probably means we forgot to reset the build
        // queue.  Thus, if hammers are being produced it is worth
        // warning about, but not if producing tools.
        if (any(queues, BuildQueue::isEmpty)
            && any(spec.getGoodsTypeList(), g ->
                (g.isBuildingMaterial()
                    && !g.isRawMaterial()
                    && !g.isBreedable()
                    && getAdjustedNetProductionOf(g) > 0
                    && neededForBuildableType(g)))) {
            cs.addMessage(owner,
                new ModelMessage(MessageType.BUILDING_COMPLETED,
                    "model.colony.notBuildingAnything", this)
                .addName("%colony%", getName()));
        }

        // Update SoL.
        updateSoL();
        if (sonsOfLiberty / 10 != oldSonsOfLiberty / 10) {
            cs.addMessage(owner,
                new ModelMessage(MessageType.SONS_OF_LIBERTY,
                                 ((sonsOfLiberty > oldSonsOfLiberty)
                                     ? "model.colony.soLIncrease"
                                     : "model.colony.soLDecrease"),
                                 this, spec.getGoodsType("model.goods.bells"))
                    .addAmount("%oldSoL%", oldSonsOfLiberty)
                    .addAmount("%newSoL%", sonsOfLiberty)
                    .addName("%colony%", getName()));

            ModelMessage govMgtMessage = checkForGovMgtChangeMessage();
            if (govMgtMessage != null) {
                cs.addMessage(owner, govMgtMessage);
            }
        }
        updateProductionBonus();
        // We have to wait for the production bonus to stabilize
        // before checking for completion of training.  This is a rare
        // case so it is not worth reordering the work location calls
        // to csNewTurn.
        for (WorkLocation workLocation : transform(getCurrentWorkLocations(),
                                                   WorkLocation::canTeach)) {
            ServerBuilding building = (ServerBuilding)workLocation;
            for (Unit teacher : building.getUnitList()) {
                building.csCheckTeach(teacher, cs);
            }
        }

        // Try to update minimally.
        if (tileDirty) {
            cs.add(See.perhaps(), tile);
        } else {
            cs.add(See.only(owner), this);
        }

        // Handle the running out of goods situation
        for (Consumer consumer : getConsumers()) {
            if (consumer instanceof ServerBuilding) {
                ServerBuilding building = (ServerBuilding)consumer;
                ProductionInfo info = building.getProductionInfo();
                if (info != null) building.csCheckMissingInput(info, cs, false);
            }
        }

        lb.add(", ");
    }
}
