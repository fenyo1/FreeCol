/**
 *  Copyright (C) 2002-2013   The FreeCol Team
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

package net.sf.freecol.common.model;

import java.awt.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import net.sf.freecol.client.gui.i18n.Messages;
import net.sf.freecol.common.io.FreeColXMLReader;
import net.sf.freecol.common.io.FreeColXMLWriter;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.Map.Position;
import net.sf.freecol.common.model.NationOptions.NationState;
import net.sf.freecol.common.model.Region.RegionType;
import net.sf.freecol.common.option.OptionGroup;
import net.sf.freecol.common.util.Utils;

import org.w3c.dom.Element;


/**
 * Represents a player.  The player can be either a human player or an
 * AI-player, which is further subdivided by PlayerType.
 *
 * In addition to storing the name, nation etc of the player, it also
 * stores various defaults for the player.  One example of this is the
 * {@link #getEntryLocation entry location}.
 */
public class Player extends FreeColGameObject implements Nameable {

    private static final Logger logger = Logger.getLogger(Player.class.getName());
    //
    // Types
    //

    /** Types of players. */
    public static enum PlayerType {
        NATIVE, COLONIAL, REBEL, INDEPENDENT, ROYAL, UNDEAD, RETIRED
    }

    /** Colony value categories. */
    public static enum ColonyValueCategory {
        A_OVERRIDE, // override slot containing showstopper NoValueType values
        A_PROD,     // general production level
        A_TILE,     // strangeness with the tile
        A_EUROPE,   // proximity to Europe
        A_RESOURCE, // penalize building on top of a resource
        A_ADJACENT, // penalize adjacent units and settlement-owned-tiles
        A_FOOD,     // penalize food shortage
        A_LEVEL,    // reward high production potential
        A_NEARBY,   // penalize nearby units and settlements
        A_GOODS,    // check sufficient critical goods available (e.g. lumber)
        // A_GOODS must be last, the spec is entitled to require checks on
        // as many goods types as it likes
    }

    /** Special return values for showstopper getColonyValue fail. */
    public static enum NoValueType {
        BOGUS(-1), TERRAIN(-2), RUMOUR(-3), SETTLED(-4), FOOD(-5), INLAND(-6), POLAR(-7);
     
        private static final int MAX = values().length;

        private int value;


        NoValueType(int value) { this.value = value; }

        public int getValue() { return value; }

        public double getDouble() { return (double)value; }

        public static NoValueType fromValue(int i) {
            int n = -i - 1;
            return (n >= 0 && n < MAX) ? NoValueType.values()[n] : BOGUS;
        }
    }

    /**
     * A predicate that can be applied to a unit.
     */
    public abstract class UnitPredicate {
        public abstract boolean obtains(Unit unit);
    }

    /**
     * A predicate for determining active units.
     */
    public class ActivePredicate extends UnitPredicate {

        private final Player player;

        /**
         * Creates a new active predicate.
         *
         * @param player The owning <code>Player</code>.
         */
        public ActivePredicate(Player player) {
            this.player = player;
        }

        /**
         * Is the unit active and going nowhere, and thus available to
         * be moved by the player?
         *
         * @return True if the unit can be moved.
         */
        public boolean obtains(Unit unit) {
            return unit.couldMove()
                && unit.getState() != Unit.UnitState.SKIPPED;
        }
    }

    /**
     * A predicate for determining units going somewhere.
     */
    public class GoingToPredicate extends UnitPredicate {

        private final Player player;
        private final boolean tradeRoute;


        /**
         * Creates a new going-to predicate.
         *
         * @param player The owning <code>Player</code>.
         * @param tradeRoute Whether this should be a unit following a
         *     trade route.
         */
        public GoingToPredicate(Player player, boolean tradeRoute) {
            this.player = player;
            this.tradeRoute = tradeRoute;
        }

        /**
         * Does this unit have orders to go somewhere?
         *
         * @return True if the unit has orders to go somewhere.
         */
        public boolean obtains(Unit unit) {
            return !unit.isDisposed()
                && unit.getOwner() == player
                && unit.getState() != Unit.UnitState.FORTIFYING
                && unit.getState() != Unit.UnitState.SKIPPED
                && unit.getMovesLeft() > 0
                && !unit.isDamaged()
                && !unit.isAtSea()
                && !unit.isOnCarrier()
                && !unit.isInColony()
                && ((!tradeRoute && unit.getDestination() != null)
                    || (tradeRoute && unit.getTradeRoute() != null));
        }
    }

    /**
     * An <code>Iterator</code> of {@link Unit}s that can be made active.
     */
    public class UnitIterator implements Iterator<Unit> {

        private Player owner;

        private UnitPredicate predicate;

        private List<Unit> units = null;

        /**
         * A comparator to compare units by position, top to bottom,
         * left to right.
         */
        private final Comparator<Unit> xyComparator = new Comparator<Unit>() {
            public int compare(Unit unit1, Unit unit2) {
                Tile tile1 = unit1.getTile();
                Tile tile2 = unit2.getTile();
                int cmp = ((tile1 == null) ? 0 : tile1.getY())
                    - ((tile2 == null) ? 0 : tile2.getY());
                return (cmp != 0 || tile1 == null || tile2 == null) ? cmp
                    : (tile1.getX() - tile2.getX());
            }
        };

        /**
         * Creates a new <code>UnitIterator</code>.
         *
         * @param owner The <code>Player</code> that needs an iterator of it's
         *            units.
         * @param predicate An object for deciding whether a <code>Unit</code>
         *            should be included in the <code>Iterator</code> or not.
         */
        public UnitIterator(Player owner, UnitPredicate predicate) {
            this.owner = owner;
            this.predicate = predicate;
            reset();
        }

        /**
         * Reset the internal units list, initially only with units that
         * satisfy the predicate.
         */
        public void reset() {
            units = new ArrayList<Unit>();
            for (Unit u : owner.getUnits()) {
                if (predicate.obtains(u)) units.add(u);
            }
            Collections.sort(units, xyComparator);
        }

        /**
         * Check if there is any more valid units.
         * If there are, it will be at the head of the internal units list.
         *
         * @return True if there are any valid units left.
         */
        public boolean hasNext() {
            // Try to find a unit that still satisfies the predicate.
            while (!units.isEmpty()) {
                if (predicate.obtains(units.get(0))) {
                    return true; // Still valid
                }
                units.remove(0);
            }
            // Nothing left, so refill the units list.  If it is still
            // empty then there is definitely nothing left.
            reset();
            return !units.isEmpty();
        }

        /**
         * Get the next valid unit.
         * Always call hasNext to enforce validity.
         *
         * @return The next valid unit, or null if none.
         */
        public Unit next() {
            return (hasNext()) ? units.remove(0) : null;
        }

        /**
         * Set the next valid unit.
         *
         * @param unit The <code>Unit</code> to put at the front of the list.
         * @return True if the operation succeeds.
         */
        public boolean setNext(Unit unit) {
            if (predicate.obtains(unit)) { // Of course, it has to be valid...
                Unit first = (units.isEmpty()) ? null : units.get(0);
                while (!units.isEmpty()) {
                    if (units.get(0) == unit) return true;
                    units.remove(0);
                }
                reset();
                while (!units.isEmpty() && units.get(0) != first) {
                    if (units.get(0) == unit) return true;
                    units.remove(0);
                }
            }
            return false;
        }

        /**
         * Removes from the underlying collection the last element returned by
         * the iterator (optional operation).
         *
         * @exception UnsupportedOperationException no matter what.
         */
        public void remove() {
            throw new UnsupportedOperationException();
        }

        /**
         * Removes a specific unit from this unit iterator.
         *
         * @param u The <code>Unit</code> to remove.
         * @return True if the unit was removed.
         */
        public boolean remove(Unit u) {
            return units.remove(u);
        }
    }

    /** The stance one player has towards another player. */
    public static enum Stance {
        // Valid transitions:
        //
        //   [FROM] \  [TO]  U  A  P  C  W        Reasons
        //   ----------------------------------   a = attack
        //   UNCONTACTED  |  -  x  c  x  i    |   c = contact
        //   ALLIANCE     |  x  -  d  x  adit |   d = act of diplomacy
        //   PEACE        |  x  d  -  x  adit |   i = incitement/rebellion
        //   CEASE_FIRE   |  x  d  t  -  adit |   t = change of tension
        //   WAR          |  x  d  ds dt -    |   s = surrender
        //   ----------------------------------   x = invalid
        //
        UNCONTACTED,
        ALLIANCE,
        PEACE,
        CEASE_FIRE,
        WAR;


        // Helpers to enforce valid transitions
        private void badStance() throws IllegalStateException {
            throw new IllegalStateException("Bogus stance");
        }
        private void badTransition(Stance newStance)
            throws IllegalStateException {
            throw new IllegalStateException("Bad transition: " + toString()
                                            + " -> " + newStance.toString());
        }

        /**
         * Check whether tension has changed enough to merit a stance
         * change.  Do not simply check for crossing tension
         * thresholds, add in Tension.DELTA to provide a bit of
         * hysteresis to dampen ringing.
         *
         * @param tension The <code>Tension</code> to check.
         * @return The <code>Stance</code> appropriate to the tension level.
         */
        public Stance getStanceFromTension(Tension tension) {
            int value = tension.getValue();
            switch (this) {
            case WAR: // Cease fire if tension decreases
                if (value <= Tension.Level.CONTENT.getLimit()-Tension.DELTA) {
                    return Stance.CEASE_FIRE;
                }
                break;
            case CEASE_FIRE: // Peace if tension decreases
                if (value <= Tension.Level.HAPPY.getLimit()-Tension.DELTA) {
                    return Stance.PEACE;
                }
                // Fall through
            case ALLIANCE: case PEACE: // War if tension increases
                if (value > Tension.Level.HATEFUL.getLimit()+Tension.DELTA) {
                    return Stance.WAR;
                }
                break;
            case UNCONTACTED:
                break;
            default:
                this.badStance();
            }
            return this;
        }

        /**
         * A stance change is about to happen.  Get the appropriate tension
         * modifier.
         *
         * @param newStance The new <code>Stance</code>.
         * @return A modifier to the current tension.
         */
        public int getTensionModifier(Stance newStance)
            throws IllegalStateException {
            switch (newStance) {
            case UNCONTACTED:     badTransition(newStance);
            case ALLIANCE:
                switch (this) {
                case UNCONTACTED: badTransition(newStance);
                case ALLIANCE:    return 0;
                case PEACE:       return Tension.ALLIANCE_MODIFIER;
                case CEASE_FIRE:  return Tension.ALLIANCE_MODIFIER + Tension.PEACE_TREATY_MODIFIER;
                case WAR:         return Tension.ALLIANCE_MODIFIER + Tension.CEASE_FIRE_MODIFIER + Tension.PEACE_TREATY_MODIFIER;
                default:          this.badStance();
                }
            case PEACE:
                switch (this) {
                case UNCONTACTED: return Tension.CONTACT_MODIFIER;
                case ALLIANCE:    return Tension.DROP_ALLIANCE_MODIFIER;
                case PEACE:       return 0;
                case CEASE_FIRE:  return Tension.PEACE_TREATY_MODIFIER;
                case WAR:         return Tension.CEASE_FIRE_MODIFIER + Tension.PEACE_TREATY_MODIFIER;
                default:          this.badStance();
                }
            case CEASE_FIRE:
                switch (this) {
                case UNCONTACTED: badTransition(newStance);
                case ALLIANCE:    badTransition(newStance);
                case PEACE:       badTransition(newStance);
                case CEASE_FIRE:  return 0;
                case WAR:         return Tension.CEASE_FIRE_MODIFIER;
                default:          this.badStance();
                }
            case WAR:
                switch (this) {
                case UNCONTACTED: return Tension.WAR_MODIFIER;
                case ALLIANCE:    return Tension.WAR_MODIFIER;
                case PEACE:       return Tension.WAR_MODIFIER;
                case CEASE_FIRE:  return Tension.RESUME_WAR_MODIFIER;
                case WAR:         return 0;
                default:          this.badStance();
                }
            default:
                throw new IllegalStateException("Bogus newStance");
            }
        }

        /**
         * Gets a key for this stance.
         *
         * @return The stance key.
         */
        public String getKey() {
            return toString().toLowerCase(Locale.US);
        }

        /**
         * Get a label for this stance.
         *
         * @return A <code>StringTemplate</code> for this stance.
         */
        public StringTemplate getLabel() {
            return StringTemplate.template("model.stance." + getKey());
        }
    }


    //
    // Constants
    //

    /** An ability to apply post-declaration. */
    public static final Ability ABILITY_INDEPENDENCE_DECLARED
        = new Ability(Ability.INDEPENDENCE_DECLARED, true);

    /** A comparator for ordering players. */
    public static final Comparator<Player> playerComparator
        = new Comparator<Player>() {
            public int compare(Player player1, Player player2) {
                int counter1 = 0;
                int counter2 = 0;

                if (player1.isAdmin()) counter1 += 8;
                if (!player1.isAI()) counter1 += 4;
                if (player1.isEuropean()) counter1 += 2;
                if (player2.isAdmin()) counter2 += 8;
                if (!player2.isAI()) counter2 += 4;
                if (player2.isEuropean()) counter2 += 2;
                
                return counter2 - counter1;
            }
        };

    /** A magic constant to denote that a players gold is not tracked. */
    public static final int GOLD_NOT_ACCOUNTED = Integer.MIN_VALUE;

    /**
     * A token to use for the settlement name in requests to the server
     * to ask the server to choose a settlement name.
     */
    public static final String ASSIGN_SETTLEMENT_NAME = "";

    /** The name of the unknown enemy. */
    public static final String UNKNOWN_ENEMY = "unknown enemy";


    //
    // Class variables
    //

    /**
     * The name of this player.  This defaults to the user name in
     * case of a human player and the rulerName of the NationType in
     * case of an AI player.
     */
    protected String name;

    /** The name of this player as an independent nation. */
    protected String independentNationName;

    /** The type of player. */
    protected PlayerType playerType;

    /** The player nation type. */
    protected NationType nationType;

    /** The nation identifier of this player, e.g. "model.nation.dutch". */
    protected String nationId;

    /** The name this player uses for the New World. */
    protected String newLandName = null;

    /** Is this player an admin? */
    protected boolean admin;

    /** Is this player an AI? */
    protected boolean ai;

    /** Is this player ready to start? */
    protected boolean ready;

    /** Is this player dead? */
    protected boolean dead = false;

    /** True if player has been attacked by privateers. */
    protected boolean attackedByPrivateers = false;

    /**
     * Whether the player is bankrupt, i.e. unable to pay for the
     * maintenance of all buildings.
     */
    private boolean bankrupt;

    /** The current score of this player. */
    protected int score;

    /** The amount of gold this player owns. */
    protected int gold;

    /**
     * The number of immigration points.  Immigration points are an
     * abstract game concept.  They are generated by but are not
     * identical to crosses.
     */
    protected int immigration;

    /**
     * The amount of immigration needed until the next unit decides
     * to migrate.
     */
    protected int immigrationRequired;

    /**
     * The number of liberty points.  Liberty points are an
     * abstract game concept.  They are generated by but are not
     * identical to bells.
     */
    protected int liberty;

    /** SoL from last turn. */
    protected int oldSoL = 0;

    /** The number of liberty bells produced towards the intervention force. */
    protected int interventionBells;

    /** The current tax rate for this player. */
    protected int tax = 0;

    /** The player starting location on the map. */
    protected Location entryLocation;

    /** The market for Europe. */
    protected Market market;

    /** The European port/location for this player. */
    protected Europe europe;

    /** The monarch for this player. */
    protected Monarch monarch;

    /** The founding fathers in this Player's congress. */
    final protected Set<FoundingFather> foundingFathers
        = new HashSet<FoundingFather>();
    /** Current founding father being recruited. */
    protected FoundingFather currentFather;
    /** The offered founding fathers. */
    final protected List<FoundingFather> offeredFathers
        = new ArrayList<FoundingFather>();

    /**
     * The tension levels, 0-1000, with 1000 being maximum hostility.
     * Only used by AI.  TODO: move this to AIPlayer
     */
    protected java.util.Map<Player, Tension> tension
        = new HashMap<Player, Tension>();

    /**
     * Stores the stance towards the other players. One of: WAR, CEASE_FIRE,
     * PEACE and ALLIANCE.
     */
    protected java.util.Map<String, Stance> stance
        = new HashMap<String, Stance>();

    /** The trade routes defined by this player. */
    protected final List<TradeRoute> tradeRoutes = new ArrayList<TradeRoute>();

    /** The current model messages for this player. */
    protected final List<ModelMessage> modelMessages
        = new ArrayList<ModelMessage>();

    /** The history events occuring with this player. */
    protected final List<HistoryEvent> history = new ArrayList<HistoryEvent>();

    /** The last-sale data. */
    protected HashMap<String, LastSale> lastSales = null;

    /** A map of indices of the largest used region name by type. */
    protected final HashMap<String, Integer> nameIndex
        = new HashMap<String, Integer>();

    // Temporary/transient variables, do not serialize.

    /** The units this player owns. */
    private final List<Unit> units = new ArrayList<Unit>();

    /** The settlements this player owns. */
    protected final List<Settlement> settlements
        = new ArrayList<Settlement>();

    /** The tiles the player can see. */
    private boolean[][] canSeeTiles = null;
    /** Are the canSeeTiles valid or do they need to be recalculated? */
    private boolean canSeeValid = false;
    /** Do not access canSeeTiles without taking canSeeLock. */
    private final Object canSeeLock = new Object();

    /** A container for the abilities and modifiers of this type. */
    protected final FeatureContainer featureContainer = new FeatureContainer();

    /** The maximum food consumption of unit types available to this player. */
    private int maximumFoodConsumption = -1;

    /** An iterator for the player units that are still active this turn. */
    private final UnitIterator nextActiveUnitIterator
        = new UnitIterator(this, new ActivePredicate(this));

    /** An iterator for the player units that have a destination to go to. */
    private final UnitIterator nextGoingToUnitIterator
        = new UnitIterator(this, new GoingToPredicate(this, false));

    /** An iterator for the player units that have a trade route to follow. */
    private final UnitIterator nextTradeRouteUnitIterator
        = new UnitIterator(this, new GoingToPredicate(this, true));

    /**
     * The HighSeas is a Location that enables Units to travel between
     * the New World and one or several European Ports.
     */
    protected HighSeas highSeas = null;

    /** A cache of settlement names. */
    protected List<String> settlementNames = null;
    /** The name of the national capital.  (Only used by natives ATM). */
    protected String capitalName = null;

    /** A cache of ship names. */
    protected List<String> shipNames = null;
    /** A fallback ship name prefix. */
    protected String shipFallback = null;


    //
    // Constructors
    //

    /**
     * Constructor for ServerPlayer.
     *
     * @param game The enclosing <code>Game</code>.
     */
    protected Player(Game game) {
        super(game);
    }

    /**
     * Initiates a new <code>Player</code> from an <code>Element</code> and
     * registers this <code>Player</code> at the specified game.
     *
     * @param game The enclosing <code>Game</code>.
     * @param e An XML-element that will be used to initialize this object.
     *
     */
    public Player(Game game, Element e) {
        super(game, null);

        readFromXMLElement(e);
    }

    /**
     * Creates a new <code>Player</code> with the given id.  The object
     * should later be initialized by calling either
     * {@link #readFromXML(FreeColXMLReader)} or
     * {@link #readFromXMLElement(Element)}.
     *
     * @param game The <code>Game</code> this object belongs to.
     * @param id The object identifier.
     */
    public Player(Game game, String id) {
        super(game, id);
    }


    //
    // Names and naming
    //

    /**
     * Gets the name of this player.
     *
     * @return The name of this player.
     */
    public String getName() {
        return name;
    }

    // TODO: remove this again
    public String getNameKey() {
        return getName();
    }

    /**
     * Is this player the unknown enemy?
     *
     * @return True if this player is the unknown enemy.
     */
    public boolean isUnknownEnemy() {
        return UNKNOWN_ENEMY.equals(name);
    }

    /**
     * Gets the name to display for this player.
     *
     * TODO: This is a kludge that should be fixed.
     *
     * @return The name to display for this player.
     */
    public String getDisplayName() {
        return (getName().startsWith("model.nation."))
            ? Messages.message(getName())
            : getName();
    }

    /**
     * Set the player name.
     *
     * @param newName The new name value.
     */
    public void setName(String newName) {
        this.name = newName;
    }

    /**
     * Get the new post-declaration player name.
     *
     * @return The post-declaration player name.
     */
    public final String getIndependentNationName() {
        return independentNationName;
    }

    /**
     * Set the post-declaration player name.
     *
     * @param newIndependentNationName The new player name.
     */
    public final void setIndependentNationName(final String newIndependentNationName) {
        this.independentNationName = newIndependentNationName;
    }

    /**
     * Gets the name this player has chosen for the new world.
     *
     * @return The name of the new world as chosen by the <code>Player</code>,
     *     or null if none chosen yet.
     */
    public String getNewLandName() {
        return newLandName;
    }

    /**
     * Has the player already selected a name for the new world?
     *
     * @return True if the new world has been named by this player.
     */
    public boolean isNewLandNamed() {
        return newLandName != null;
    }

    /**
     * Sets the name this player uses for the new world.
     *
     * @param newLandName This <code>Player</code>'s name for the new world.
     */
    public void setNewLandName(String newLandName) {
        this.newLandName = newLandName;
    }

    /**
     * Get a name key for the player Europe.
     *
     * @return A name key, or null if Europe is null.
     */
    public String getEuropeNameKey() {
        return (europe == null) ? null : nationId + ".europe";
    }

    /**
     * Gets a name key for the nation name.
     *
     * @return A nation name key.
     */
    public String getNationNameKey() {
        return nationId.substring(nationId.lastIndexOf('.')+1)
            .toUpperCase(Locale.US);
    }

    /**
     * Get a template for this players nation name.
     *
     * @return A template for this nation name.
     */
    public StringTemplate getNationName() {
        return (playerType == PlayerType.REBEL
                || playerType == PlayerType.INDEPENDENT)
            ? StringTemplate.name(independentNationName)
            : StringTemplate.key(nationId + ".name");
    }

    /**
     * Get a name key for the player nation ruler.
     *
     * @return The ruler name key.
     */
    public final String getRulerNameKey() {
        return nationId + ".ruler";
    }

    /**
     * Get a resource key for the player monarch image.
     *
     * @return The monarch image key.
     */
    public String getMonarchKey() {
        return nationId + ".monarch.image";
    }

    /**
     * Gets the name index for a given key.
     *
     * @param key The key to use.
     */
    public int getNameIndex(String key) {
        Integer val = nameIndex.get(key);
        return (val == null) ? 0 : val;
    }

    /**
     * Sets the name index for a given key.
     *
     * @param key The key to use.
     * @param value The new value.
     */
    public void setNameIndex(String key, int value) {
        nameIndex.put(key, new Integer(value));
    }

    /**
     * What is the name of the player's market?
     * Following a declaration of independence we are assumed to trade
     * broadly with any European market rather than a specific port.
     *
     * @return A <code>StringTemplate</code> for the player market.
     */
    public StringTemplate getMarketName() {
        return (getEurope() == null)
            ? StringTemplate.key("model.market.independent")
            : StringTemplate.key(nationId + ".europe");
    }

    /**
     * Installs suitable settlement names (and the capital if native)
     * into the player name cache.
     *
     * @param random An optional pseudo-random number source.
     */
    private void initializeSettlementNames(Random random) {
        if (settlementNames == null) {
            settlementNames = new ArrayList<String>();
            settlementNames.addAll(Messages.getSettlementNames(this));
            if (isIndian()) {
                capitalName = settlementNames.remove(0);
                if (random != null) {
                    Utils.randomShuffle(logger, "Settlement names",
                                        settlementNames, random);
                }
            } else {
                capitalName = null;
            }
            logger.info("Installed " + settlementNames.size()
                + " settlement names for player " + this);
        }
    }

    /**
     * Gets the name of this players capital.  Only meaningful to natives.
     *
     * @param random An optional pseudo-random number source.
     * @return The name of this players capital.
     */
    public String getCapitalName(Random random) {
        if (isEuropean()) return null;
        if (capitalName == null) initializeSettlementNames(random);
        return capitalName;
    }

    /**
     * Gets a settlement name suitable for this player.
     *
     * @param random An optional pseudo-random number source.
     * @return A new settlement name.
     */
    public String getSettlementName(Random random) {
        Game game = getGame();
        if (settlementNames == null) initializeSettlementNames(random);

        // Try the names in the players national name list.
        while (!settlementNames.isEmpty()) {
            String name = settlementNames.remove(0);
            if (game.getSettlement(name) == null) return name;
        }

        // Fallback method
        final String base = Messages.message((isEuropean()) ? "Colony"
            : "Settlement") + "-";
        String name;
        int i = settlements.size() + 1;
        while (game.getSettlement(name = base + Integer.toString(i)) != null) {
            i++;
        }
        return name;
    }

    /**
     * Installs suitable ships names into the player name cache.
     * Optionally shuffles all but the first name so so as to provide
     * a stable starting ship name.
     *
     * @param random A <code>Random</code> number source.
     */
    private void initializeShipNames(Random random) {
        if (shipNames == null) {
            shipNames = new ArrayList<String>();
            shipNames.addAll(Messages.getShipNames(this));
            shipFallback = (shipNames.isEmpty()) ? null
                : shipNames.remove(0);
            String startingShip = (shipNames.isEmpty()) ? null
                : shipNames.remove(0);
            if (random != null) {
                Utils.randomShuffle(logger, "Ship names", shipNames, random);
            }
            if (startingShip != null) shipNames.add(0, startingShip);
            logger.info("Installed " + shipNames.size()
                + " ship names for player " + this.toString());
        }
    }

    /**
     * Gets a new name for a unit.
     *
     * Currently only names naval units, not specific to type.
     * TODO: specific names for types.
     *
     * @param type The <code>UnitType</code> to choose a name for.
     * @param random A pseudo-random number source.
     * @return A name for the unit, or null if not available.
     */
    public String getNameForUnit(UnitType type, Random random) {
        String name;

        if (!type.isNaval()) return null;

        // Collect all the names of existing naval units.
        List<String> navalNames = new ArrayList<String>();
        for (Unit u : getUnits()) {
            if (u.isNaval() && u.getName() != null) {
                navalNames.add(u.getName());
            }
        }

        // Find a new name in the installed ship names if possible.
        if (shipNames == null) initializeShipNames(random);
        while (!shipNames.isEmpty()) {
            name = shipNames.remove(0);
            if (!navalNames.contains(name)) return name;
        }

        // Fallback method
        if (shipFallback != null) {
            final String base = shipFallback + "-";
            int i = 0;
            while (navalNames.contains(name = base + Integer.toString(i))) i++;
            return name;
        }

        return null;
    }


    //
    // Player / nation types and the implications thereof
    //

    /**
     * Get the type of this player.
     *
     * @return The player type.
     */
    public PlayerType getPlayerType() {
        return playerType;
    }

    /**
     * Sets the player type.
     *
     * @param type The new player type.
     * @see #getPlayerType
     */
    public void setPlayerType(PlayerType type) {
        playerType = type;
    }

    /**
     * Checks if this player is colonial, and thus can recruit units
     * by producing immigration.
     *
     * @return True if this player is colonial.
     */
    public boolean isColonial() {
        return playerType == PlayerType.COLONIAL;
    }

    /**
     * Checks if this player is European, with does include the REF.
     *
     * @return True if this player is European.
     */
    public boolean isEuropean() {
        return playerType == PlayerType.COLONIAL
            || playerType == PlayerType.REBEL
            || playerType == PlayerType.INDEPENDENT
            || playerType == PlayerType.ROYAL;
    }

    /**
     * Is this a native player?
     *
     * @return True if this player is a native player.
     */
    public boolean isIndian() {
        return playerType == PlayerType.NATIVE;
    }

    /**
     * Is this an undead player?
     *
     * @return True if this player is undead.
     */
    public boolean isUndead() {
        return playerType == PlayerType.UNDEAD;
    }

    /**
     * Is this a REF player?
     *
     * @return True if this is a REF player.
     */
    public boolean isREF() {
        return nationType != null && nationType.isREF();
    }

    /**
     * Get the nation type of this player.
     *
     * @return The <code>NationType</code> of this player.
     */
    public NationType getNationType() {
        return nationType;
    }

    /**
     * Sets the nation type of this player.
     *
     * @param newNationType The new <code>NationType</code>.
     */
    public void setNationType(NationType newNationType) {
        nationType = newNationType;
    }

    /**
     * Changes the nation type of this player, handling the features.
     *
     * @param newNationType The new <code>NationType</code>.
     */
    public void changeNationType(NationType newNationType) {
        if (nationType != null) {
            removeFeatures(nationType, "Changing type from " + nationType
                + " to " + newNationType + " for " + this);
        }
        setNationType(newNationType);
        if (newNationType != null) addFeatures(newNationType);
    }

    /**
     * Can this player build colonies?
     *
     * @return True if this player can found colonies.
     */
    public boolean canBuildColonies() {
        return nationType.hasAbility(Ability.FOUNDS_COLONIES);
    }

    /**
     * Can this player recruit founding fathers?
     *
     * @return True if this player can recruit founding fathers.
     */
    public boolean canHaveFoundingFathers() {
        return nationType.hasAbility(Ability.ELECT_FOUNDING_FATHER);
    }

    /**
     * Get the identifier for this Player's nation.
     *
     * @return The nation identifier.
     */
    public String getNationId() {
        return nationId;
    }

    /**
     * Gets this Player's nation.
     *
     * @return The player <code>Nation</code>, or null if the unknown enemy.
     */
    public Nation getNation() {
        return (isUnknownEnemy()) ? null
            : getSpecification().getNation(nationId);
    }

    /**
     * Sets the nation for this player.
     *
     * @param newNation The new <code>Nation</code>.
     */
    public void setNation(Nation newNation) {
        Nation oldNation = getNation();
        nationId = newNation.getId();
        java.util.Map<Nation, NationState> nations
            = getGame().getNationOptions().getNations();
        nations.put(oldNation, NationState.AVAILABLE);
        nations.put(newNation, NationState.NOT_AVAILABLE);
    }

    /**
     * Is this player an admin.
     *
     * @return True if the player is an admin.
     */
    public boolean isAdmin() {
        return admin;
    }

    /**
     * Is this an AI player?
     *
     * @return True if this is an AI player.
     */
    public boolean isAI() {
        return ai;
    }

    /**
     * Sets whether this player is an AI player.
     *
     * @param ai The AI player value.
     */
    public void setAI(boolean ai) {
        this.ai = ai;
    }

    /**
     * Is this player ready to start the game?
     *
     * @return True if this <code>Player</code> is ready to start the game.
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * Sets this players readiness state.
     *
     * @param ready The new readiness state.
     */
    public void setReady(boolean ready) {
        this.ready = ready;
    }

    /**
     * Checks if this player is dead.  A <code>Player</code> dies when it
     * loses the game.
     *
     * @return True if this <code>Player</code> is dead.
     */
    public boolean isDead() {
        return dead;
    }

    /**
     * Get the player death state.
     * This is indeed identical to isDead(), but is needed for partial
     * updates to complement the setDead() function.
     *
     * @return True if this <code>Player</code> is dead.
     */
    public boolean getDead() {
        return dead;
    }

    /**
     * Sets this player to be dead or not.
     *
     * @param dead The new death state.
     * @see #getDead
     */
    public void setDead(boolean dead) {
        this.dead = dead;
    }

    /**
     * Has player has been attacked by privateers?
     *
     * @return True if this player has been attacked by privateers.
     */
    public boolean getAttackedByPrivateers() {
        return attackedByPrivateers;
    }

    /**
     * Sets whether this player has been attacked by privateers.
     *
     * @param attacked True if the player has been attacked by privateers.
     */
    public void setAttackedByPrivateers(boolean attacked) {
        attackedByPrivateers = attacked;
    }

    /**
     * Checks if this player has work to do if it is a REF-player.
     *
     * @return True if any of our units are located in the new
     *     world or a nation is in rebellion against us.
     */
    public boolean isWorkForREF() {
        for (Unit u : getUnits()) { // Work to do if unit in the new world
            if (u.hasTile()) return true;
        }
        return !getRebels().isEmpty();
    }

    /**
     * Gets a list of the players in rebellion against this (REF) player.
     *
     * @return A list of nations in rebellion against us.
     */
    public List<Player> getRebels() {
        List<Player> rebels = new ArrayList<Player>();
        for (Player p : getGame().getLiveEuropeanPlayers()) {
            if (p.getREFPlayer() == this
                && (p.getPlayerType() == PlayerType.REBEL
                    || p.getPlayerType() == PlayerType.UNDEAD)) rebels.add(p);
        }
        return rebels;
    }

    /**
     * Gets the <code>Player</code> controlling the "Royal Expeditionary
     * Force" for this player.
     *
     * @return The player, or <code>null</code> if this player does not have a
     *         royal expeditionary force.
     */
    public Player getREFPlayer() {
        Nation ref = (isUnknownEnemy()) ? null : getNation().getREFNation();
        return (ref == null) ? null : getGame().getPlayer(ref.getId());
    }

    /**
     * Gets the player nation color.
     *
     * @return The <code>Color</code>.
     */
    public Color getNationColor() {
        Color color;
        return (isUnknownEnemy()) ? Nation.UNKNOWN_NATION_COLOR
            : ((color = getNation().getColor()) != null) ? color
            : getNation().forceDefaultColor();
    }


    //
    // Scoring and finance
    //

    /**
     * Gets the current score of the player.
     *
     * @return The score.
     */
    public int getScore() {
        return score;
    }

    /**
     * Set the current score of the player.
     *
     * @param score The new score.
     */
    public void setScore(int score) {
        this.score = score;
    }

    /**
     * Gets the score by which we decide the weakest and strongest AI
     * players for the Spanish Succession event.
     *
     * @return A strength score.
     */
    public int getSpanishSuccessionScore() {
        // TODO: try getColoniesPopulation.
        return getScore();
    }

    /**
     * Get the amount of gold that this player has.
     *
     * Some players do not account their gold.  These players return
     * GOLD_NOT_ACCOUNTED.
     *
     * @return The amount of gold that this player has.
     */
    public int getGold() {
        return gold;
    }

    /**
     * Set the amount of gold that this player has.
     *
     * @param newGold The new player gold value.
     */
    public void setGold(int newGold) {
        gold = newGold;
    }

    /**
     * Checks if the player has enough gold to make a purchase.
     * Use this rather than comparing with getGold(), as this handles
     * players that do not account for gold.
     *
     * @param amount The purchase price to check.
     * @return True if the player can afford the purchase.
     */
    public boolean checkGold(int amount) {
        return this.gold == GOLD_NOT_ACCOUNTED || this.gold >= amount;
    }

    /**
     * Modifies the amount of gold that this player has.  The argument can be
     * both positive and negative.
     *
     * @param amount The amount of gold to be added to this player.
     * @return The amount of gold post-modification.
     */
    public int modifyGold(int amount) {
        if (this.gold != Player.GOLD_NOT_ACCOUNTED) {
            if ((gold + amount) >= 0) {
                gold += amount;
            } else {
                // This can happen if the server and the client get
                // out of sync.  Perhaps it can also happen if the
                // client tries to adjust gold for another player,
                // where the balance is unknown. Just keep going and
                // do the best thing possible, we don't want to crash
                // the game here.
                logger.warning("Cannot add " + amount + " gold for "
                    + this + ": would be negative!");
                gold = 0;
            }
        }
        return gold;
    }

    /**
     * Get the bankruptcy state.
     *
     * isBankrupt would be nicer, but the introspector expects getBankrupt.
     *
     * @return True if this player is bankrupt.
     */
    public final boolean getBankrupt() {
        return bankrupt;
    }

    /**
     * Set the bankruptcy state.
     *
     * @param newBankrupt The new bankruptcy value.
     */
    public final void setBankrupt(final boolean newBankrupt) {
        this.bankrupt = newBankrupt;
    }


    //
    // Migration
    //

    /**
     * Gets the amount of immigration this player possess.
     *
     * @return The immigration value.
     * @see #reduceImmigration
     */
    public int getImmigration() {
        return (isColonial()) ? immigration : 0;
    }

    /**
     * Sets the amount of immigration this player possess.
     *
     * @param immigration The immigration value for this player.
     */
    public void setImmigration(int immigration) {
        if (!isColonial()) return;
        this.immigration = immigration;
    }

    /**
     * Sets the number of immigration this player possess.
     */
    public void reduceImmigration() {
        if (!isColonial()) return;

        int cost = getSpecification()
            .getBoolean(GameOptions.SAVE_PRODUCTION_OVERFLOW)
            ? immigrationRequired : immigration;
        if (cost > immigration) {
            immigration = 0;
        } else {
            immigration -= cost;
        }
    }

    /**
     * Modify the player immigration.
     *
     * @param amount The amount to modify the immigration by.
     */
    public void modifyImmigration(int amount) {
        immigration = Math.max(0, immigration + amount);
    }

    /**
     * Gets the amount of immigration required to cause a new colonist
     * to emigrate.
     *
     * @return The immigration points required to trigger emigration.
     */
    public int getImmigrationRequired() {
        return immigrationRequired;
    }

    /**
     * Sets the number of immigration required to cause a new colonist
     * to emigrate.
     *
     * @param immigrationRequired The new number of immigration points.
     */
    public void setImmigrationRequired(int immigrationRequired) {
        this.immigrationRequired = immigrationRequired;
    }

    /**
     * Updates the amount of immigration needed to emigrate a <code>Unit</code>
     * from <code>Europe</code>.
     */
    public void updateImmigrationRequired() {
        if (!isColonial()) return;

        final Specification spec = getSpecification();
        final int current = immigrationRequired;
        int base = spec.getInteger(GameOptions.CROSSES_INCREMENT);
        // If the religious unrest bonus is present, immigrationRequired
        // has already been reduced.  We want to apply the bonus to the
        // sum of the *unreduced* immigration target and the increment.
        int unreduced = (int)Math.round(current
            / applyModifier(1.0f, Modifier.RELIGIOUS_UNREST_BONUS));
        immigrationRequired = (int)applyModifier(unreduced + base,
            Modifier.RELIGIOUS_UNREST_BONUS);;
        logger.finest("Immigration for " + getId() + " updated " + current
            + " -> " + immigrationRequired);
    }

    /**
     * Should a new colonist emigrate?
     *
     * @return Whether a new colonist should emigrate.
     */
    public boolean checkEmigrate() {
        return (isColonial()) ? getImmigrationRequired() <= immigration
            : false;
    }

    /**
     * How many new colonists should emigrate?
     *
     * @return The number of new colonists should emigrate.
     */
    public int countEmigrate() {
        return (isColonial()) ? immigration / getImmigrationRequired()
            : 0;
    }


    //
    // Liberty and founding fathers
    //

    /**
     * Gets the current amount of liberty points this player has.
     * Liberty is regularly reduced to pay for a founding father.
     *
     * @return The amount of liberty points.
     */
    public int getLiberty() {
        return (canHaveFoundingFathers()) ? liberty : 0;
    }

    /**
     * Gets the effective amount of liberty points this player has.
     * That is, the normal liberty amount post application of
     * modifiers.
     *
     * @return The effective amount of liberty points.
     */
    public int getEffectiveLiberty() {
        return (int)applyModifier((float)getLiberty(), Modifier.LIBERTY,
                                  null, getGame().getTurn());
    }

    /**
     * Sets the current amount of liberty this player has.
     *
     * @param liberty The new amount of liberty.
     */
    public void setLiberty(int liberty) {
        if (!canHaveFoundingFathers()) return;
        this.liberty = liberty;
    }

    /**
     * Modifies the current amount of liberty this player has.
     *
     * @param amount The amount of liberty to add.
     */
    public void modifyLiberty(int amount) {
        setLiberty(Math.max(0, liberty + amount));
        if (playerType == PlayerType.REBEL) interventionBells += amount;
    }

    /**
     * Recalculate bells bonus when tax changes.
     *
     * @return True if a bells bonus was set.
     */
    protected boolean recalculateBellsBonus() {
        Set<Modifier> libertyBonus = getModifierSet("model.goods.bells");
        boolean ret = false;
        for (Ability ability : getAbilitySet(Ability.ADD_TAX_TO_BELLS)) {
            FreeColObject source = ability.getSource();
            if (source != null) {
                for (Modifier modifier : libertyBonus) {
                    if (source.equals(modifier.getSource())) {
                        modifier.setValue(tax);
                        ret = true;
                    }
                }
            }
        }
        return ret;
    }

    /**
     * Gets how much liberty will be produced next turn if no colonies
     * are lost and nothing unexpected happens.
     *
     * @return The total amount of liberty this <code>Player</code>'s
     *     <code>Colony</code>s will make next turn.
     */
    public int getLibertyProductionNextTurn() {
        int nextTurn = 0;
        for (Colony colony : getColonies()) {
            for (GoodsType libertyGoods : getSpecification()
                     .getLibertyGoodsTypeList()) {
                nextTurn += colony.getTotalProductionOf(libertyGoods);
            }
        }
        return (int)applyModifier((float)nextTurn, Modifier.LIBERTY,
                                  null, getGame().getTurn());
    }

    /**
     * Gets the total percentage of rebels in all this player's colonies.
     *
     * @return The total percentage of rebels in all this player's colonies.
     */
    public int getSoL() {
        int sum = 0;
        int number = 0;
        for (Colony c : getColonies()) {
            sum += c.getSoL();
            number++;
        }
        return (number > 0) ? sum / number : 0;
    }

    /**
     * Gets the founding fathers in this player's congress.
     *
     * @return A set of <code>FoundingFather</code>s in congress.
     */
    public Set<FoundingFather> getFathers() {
        return foundingFathers;
    }

    /**
     * Does this player have a certain Founding father.
     *
     * @param someFather The <code>FoundingFather</code> to check.
     * @return Whether this player has this Founding father
     * @see FoundingFather
     */
    public boolean hasFather(FoundingFather someFather) {
        return foundingFathers.contains(someFather);
    }

    /**
     * Gets the number of founding fathers in this players congress. 
     * Used to calculate number of liberty needed to recruit new fathers.
     *
     * @return The number of founding fathers in this players congress
     */
    public int getFatherCount() {
        return foundingFathers.size();
    }

    /**
     * Add a founding father to the congress.
     *
     * @param father The <code>FoundingFather</code> to add.
     */
    public void addFather(FoundingFather father) {
        foundingFathers.add(father);
        addFeatures(father);
        for (Colony colony : getColonies()) colony.invalidateCache();
    }

    /**
     * Gets the {@link FoundingFather founding father} this player is working
     * towards.
     *
     * @return The current <code>FoundingFather</code>, or null if
     *     there is none.
     * @see #setCurrentFather
     * @see FoundingFather
     */
    public FoundingFather getCurrentFather() {
        return currentFather;
    }

    /**
     * Sets the current founding father to recruit.
     *
     * @param someFather The <code>FoundingFather</code> to recruit.
     * @see FoundingFather
     */
    public void setCurrentFather(FoundingFather someFather) {
        currentFather = someFather;
    }

    /**
     * Gets the offered fathers for this player.
     *
     * @return A list of the current offered <code>FoundingFather</code>s.
     */
    public List<FoundingFather> getOfferedFathers() {
        return offeredFathers;
    }

    /**
     * Clear the set of offered fathers.
     */
    public void clearOfferedFathers() {
        offeredFathers.clear();
    }

    /**
     * Sets the set of offered fathers.
     *
     * @param fathers A list of <code>FoundingFather</code>s to offer.
     */
    public void setOfferedFathers(List<FoundingFather> fathers) {
        clearOfferedFathers();
        offeredFathers.addAll(fathers);
    }

    /**
     * Gets the number of liberty points needed to recruit the next
     * founding father.
     *
     * @return How many more liberty points the <code>Player</code>
     *     needs in order to recruit the next <code>FoundingFather</code>.
     */
    public int getRemainingFoundingFatherCost() {
        return getTotalFoundingFatherCost() - getEffectiveLiberty();
    }

    /**
     * How many liberty points in total are needed to earn the
     * Founding Father we are trying to recruit.  See
     * https://sourceforge.net/p/freecol/bugs/2623 where the Col1
     * numbers were checked.
     *
     * @return Total number of liberty points the <code>Player</code>
     *     needs to recruit the next <code>FoundingFather</code>.
     */
    public int getTotalFoundingFatherCost() {
        final Specification spec = getSpecification();
        int base = spec.getInteger(GameOptions.FOUNDING_FATHER_FACTOR);
        int count = getFatherCount();
        return (count == 0) ? base : 2 * (count + 1) * base + 1;
    }

    /**
     * Gets the <code>Turn</code>s during which FoundingFathers were
     * elected to the Continental Congress
     *
     * @return A map of father id to <code>Turn</code>s.
     */
    public java.util.Map<String, Turn> getElectionTurns() {
        java.util.Map<String, Turn> result = new HashMap<String, Turn>();
        for (HistoryEvent e : getHistory()) {
            if (e.getEventType() == HistoryEvent.EventType.FOUNDING_FATHER) {
                result.put(e.getReplacement("%father%").getId(),
                           e.getTurn());
            }
        }
        return result;
    }

    //
    // Taxation and trade
    //

    /**
     * Get the current tax.
     *
     * @return The current tax.
     */
    public int getTax() {
        return tax;
    }

    /**
     * Sets the current tax
     *
     * @param amount The new tax amount.
     */
    public void setTax(int amount) {
        tax = amount;
        if (recalculateBellsBonus()) {
            for (Colony colony : getColonies()) colony.invalidateCache();
        }
    }

    /**
     * Get this player's Market.
     *
     * @return The <code>Market</code>.
     */
    public Market getMarket() {
        return market;
    }

    /**
     * Resets this player's Market.
     */
    public void reinitialiseMarket() {
        market = new Market(getGame(), this);
    }

    /**
     * Gets the current sales data for a location and goods type.
     *
     * @param where The <code>Location</code> of the sale.
     * @param what The <code>GoodsType</code> sold.
     *
     * @return An appropriate <code>LastSaleData</code> record, or
     *     null if no appropriate sale can be found.
     */
    public LastSale getLastSale(Location where, GoodsType what) {
        return (lastSales == null) ? null
            : lastSales.get(LastSale.makeKey(where, what));
    }

    /**
     * Saves a record of a sale.
     *
     * @param sale The <code>LastSale</code> to save.
     */
    public void addLastSale(LastSale sale) {
        if (lastSales == null) lastSales = new HashMap<String, LastSale>();
        lastSales.put(sale.getId(), sale);
    }

    /**
     * Gets the last sale price for a location and goods type as a string.
     *
     * @param where The <code>Location</code> of the sale.
     * @param what The <code>GoodsType</code> sold.
     * @return An abbreviation for the sale price, or null if none found.
     */
    public String getLastSaleString(Location where, GoodsType what) {
        LastSale data = getLastSale(where, what);
        return (data == null) ? null : String.valueOf(data.getPrice());
    }

    /**
     * Gets the arrears due for a type of goods.
     *
     * @param type The <code>GoodsType</code> to check.
     * @return The arrears due for this type of goods.
     */
    public int getArrears(GoodsType type) {
        return getMarket().getArrears(type);
    }

    /**
     * Can a type of goods can be traded in Europe?
     *
     * @param type The <code>GoodsType</code> to check.
     * @return True if there are no arrears due for this type of goods.
     */
    public boolean canTrade(GoodsType type) {
        return canTrade(type, Market.Access.EUROPE);
    }

    /**
     * Can a type of goods can be traded at a specified place?
     *
     * @param type The <code>GoodsType</code> to check.
     * @param access The way the goods are traded (Europe OR Custom)
     * @return True if type of goods can be traded.
     */
    public boolean canTrade(GoodsType type, Market.Access access) {
        if (getMarket().getArrears(type) == 0) return true;

        if (access == Market.Access.CUSTOM_HOUSE) {
            if (getSpecification().getBoolean(GameOptions.CUSTOM_IGNORE_BOYCOTT)) {
                return true;
            }
            if (hasAbility(Ability.CUSTOM_HOUSE_TRADES_WITH_FOREIGN_COUNTRIES)) {
                for (Player otherPlayer : getGame().getLiveEuropeanPlayers()) {
                    if (otherPlayer != this
                        && (getStance(otherPlayer) == Stance.PEACE
                            || getStance(otherPlayer) == Stance.ALLIANCE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Get the current sales of a type of goods.
     *
     * @param goodsType The <code>GoodsType</code> to query.
     * @return The current sales.
     */
    public int getSales(GoodsType goodsType) {
        return getMarket().getSales(goodsType);
    }

    /**
     * Modifies the current sales.
     *
     * @param goodsType The <code>GoodsType</code> to modify.
     * @param amount The new sales.
     */
    public void modifySales(GoodsType goodsType, int amount) {
        getMarket().modifySales(goodsType, amount);
    }

    /**
     * Has a type of goods been traded?
     *
     * @param goodsType The <code>GoodsType</code> to check.
     * @return Whether these goods have been traded.
     */
    public boolean hasTraded(GoodsType goodsType) {
        return getMarket().hasBeenTraded(goodsType);
    }

    /**
     * Get the most valuable goods available in one of the player's
     * colonies for the purposes of choosing a threat-to-boycott.  The
     * goods must not currently be boycotted, the player must have
     * traded in it, and the amount to be discarded will not exceed
     * GoodsContainer.CARGO_SIZE.
     *
     * @return A goods object, or null if nothing suitable found.
     */
    public Goods getMostValuableGoods() {
        if (!isEuropean()) return null;

        Goods goods = null;
        int highValue = 0;
        for (Colony colony : getColonies()) {
            for (Goods g : colony.getCompactGoods()) {
                if (getArrears(g.getType()) <= 0 && hasTraded(g.getType())) {
                    int amount = Math.min(g.getAmount(),
                                          GoodsContainer.CARGO_SIZE);
                    int value = market.getSalePrice(g.getType(), amount);
                    if (value > highValue) {
                        highValue = value;
                        goods = g;
                    }
                }
            }
        }
        return goods;
    }

    /**
     * Get the current incomeBeforeTaxes.
     *
     * @param goodsType The <code>GoodsType</code> to query.
     * @return The current incomeBeforeTaxes.
     */
    public int getIncomeBeforeTaxes(GoodsType goodsType) {
        return getMarket().getIncomeBeforeTaxes(goodsType);
    }

    /**
     * Modifies the current incomeBeforeTaxes.
     *
     * @param goodsType The <code>GoodsType</code> to modify.
     * @param amount The new incomeBeforeTaxes.
     */
    public void modifyIncomeBeforeTaxes(GoodsType goodsType, int amount) {
        getMarket().modifyIncomeBeforeTaxes(goodsType, amount);
    }

    /**
     * Get the current incomeAfterTaxes.
     *
     * @param goodsType The <code>GoodsType</code> to query.
     * @return The current incomeAfterTaxes.
     */
    public int getIncomeAfterTaxes(GoodsType goodsType) {
        return getMarket().getIncomeAfterTaxes(goodsType);
    }

    /**
     * Modifies the current incomeAfterTaxes.
     *
     * @param goodsType The <code>GoodsType</code> to modify.
     * @param amount The new incomeAfterTaxes.
     */
    public void modifyIncomeAfterTaxes(GoodsType goodsType, int amount) {
        getMarket().modifyIncomeAfterTaxes(goodsType, amount);
    }


    //
    // Europe
    //

    /**
     * Gets this players Europe object.
     *
     * @return The Europe object, or null if the player is not
     *     European or indpendent.
     */
    public Europe getEurope() {
        return europe;
    }

    /**
     * Set the Europe object for a player.
     *
     * @param europe The new <code>Europe</code> object.
     */
    public void setEurope(Europe europe) {
        this.europe = europe;
    }

    /**
     * Checks if this player can move units to Europe.
     *
     * @return True if this player has an instance of <code>Europe</code>.
     */
    public boolean canMoveToEurope() {
        return getEurope() != null;
    }

    /**
     * Gets the price for a recruit in Europe.
     *
     * @return The price of a single recruit in {@link Europe}.
     */
    public int getRecruitPrice() {
        // return Math.max(0, (getCrossesRequired() - crosses) * 10);
        return getEurope().getRecruitPrice();
    }

    /**
     * Gets the price to this player to purchase a unit in Europe.
     *
     * @param au The proposed <code>AbstractUnit</code>.
     * @return The price for the unit.
     */
    public int getPrice(AbstractUnit au) {
        final Specification spec = getSpecification();
        UnitType unitType = au.getType(spec);
        Role role = au.getRole(spec);
        if (unitType.hasPrice()) {
            int price = getEurope().getUnitPrice(unitType);
            for (AbstractGoods goods : role.getRequiredGoods()) {
                int amount = goods.getAmount() * role.getMaximumCount();
                price += getMarket().getBidPrice(goods.getType(), amount);
            }
            return price * au.getNumber();
        } else {
            return INFINITY;
        }
    }

    /**
     * Gets the monarch object this player has.
     *
     * @return The <code>Monarch</code> object this player has, or null
     *     if there is no monarch.
     */
    public Monarch getMonarch() {
        return monarch;
    }

    /**
     * Sets the monarch object this player has.
     *
     * @param monarch The new <code>Monarch</code> object.
     */
    public void setMonarch(Monarch monarch) {
        this.monarch = monarch;
    }


    //
    // Units and trade routes
    //

    /**
     * Does this player's units list contain the given unit?
     *
     * @param unit The <code>Unit</code> to test.
     * @return True if the player has the unit.
     */
    public boolean hasUnit(Unit unit) {
        synchronized (units) {
            return units.contains(unit);
        }
    }

    /**
     * Get a copy of the players units.
     *
     * @return A list of the player <code>Unit</code>s.
     */
    public List<Unit> getUnits() {
        synchronized (units) {
            return new ArrayList<Unit>(units);
        }
    }

    /**
     * Get an iterator containing all the units this player owns.
     *
     * @return An <code>Iterator</code> over the player <code>Unit</code>s.
     * @see Unit
     */
    public Iterator<Unit> getUnitIterator() {
        synchronized (units) {
            return units.iterator();
        }
    }

    /**
     * Add a unit to this player.
     *
     * @param newUnit The new <code>Unit</code> value.
     * @return True if the units container changed.
     */
    public final boolean addUnit(final Unit newUnit) {
        if (newUnit == null) return false;

        // Make sure the owner of the unit is set first, before adding
        // it to the list
        if (!this.owns(newUnit)) {
            throw new IllegalStateException("Adding another players unit:"
                + newUnit.getId() + " to " + this);
        }
        if (hasUnit(newUnit)) return false;

        synchronized (units) {
            return units.add(newUnit);
        }
    }

    /**
     * Remove a unit from this player.
     *
     * @param oldUnit The <code>Unit</code> to remove.
     * @return True if the units container changed.
     */
    public boolean removeUnit(final Unit oldUnit) {
        if (oldUnit == null) return false;

        synchronized (units) {
            return units.remove(oldUnit);
        }
    }

    /**
     * Gets the carrier units that can carry the supplied unit, if one exists.
     *
     * @param unit The <code>Unit</code> to carry.
     * @return A list of suitable carriers.
     */
    public List<Unit> getCarriersForUnit(Unit unit) {
        List<Unit> ul = new ArrayList<Unit>();
        for (Unit u : getUnits()) {
            if (u.couldCarry(unit)) ul.add(u);
        }
        return ul;
    }

    /**
     * Gets the number of King's land units.
     *
     * @return The number of units
     */
    public int getNumberOfKingLandUnits() {
        int n = 0;
        for (Unit unit : getUnits()) {
            if (unit.hasAbility(Ability.REF_UNIT) && !unit.isNaval()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Checks if this player has at least one Man-of-War.
     *
     * @return True if this player owns at least one Man-of-War.
     */
    public boolean hasManOfWar() {
        Iterator<Unit> it = getUnitIterator();
        while (it.hasNext()) {
            Unit unit = it.next();
            if ("model.unit.manOWar".equals(unit.getType().getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets a new active unit.
     *
     * @return A <code>Unit</code> that can be made active.
     */
    public Unit getNextActiveUnit() {
        return nextActiveUnitIterator.next();
    }

    /**
     * Sets a new active unit.
     *
     * @param unit A <code>Unit</code> to make the next one to be active.
     * @return True if the operation succeeded.
     */
    public boolean setNextActiveUnit(Unit unit) {
        return nextActiveUnitIterator.setNext(unit);
    }

    /**
     * Checks if a new active unit can be made active.
     *
     * @return True if there is a potential active unit.
     */
    public boolean hasNextActiveUnit() {
        return nextActiveUnitIterator.hasNext();
    }

    /**
     * Gets a new going-to unit.
     *
     * @return A <code>Unit</code> that can be made active.
     */
    public Unit getNextGoingToUnit() {
        return nextGoingToUnitIterator.next();
    }

    /**
     * Sets a new going-to unit.
     *
     * @param unit A <code>Unit</code> to make the next one to be active.
     * @return True if the operation succeeded.
     */
    public boolean setNextGoingToUnit(Unit unit) {
        return nextGoingToUnitIterator.setNext(unit);
    }

    /**
     * Checks if there is a unit that has a destination.
     *
     * @return True if there is a unit with a destination.
     */
    public boolean hasNextGoingToUnit() {
        return nextGoingToUnitIterator.hasNext();
    }

    /**
     * Gets the next trade route unit.
     *
     * @return A <code>Unit</code> that has a trade route to follow.
     */
    public Unit getNextTradeRouteUnit() {
        return nextTradeRouteUnitIterator.next();
    }

    /**
     * Checks if there is a unit that has a trade route.
     *
     * @return True if there is a unit with a trade route.
     */
    public boolean hasNextTradeRouteUnit() {
        return nextTradeRouteUnitIterator.hasNext();
    }

    /**
     * Reset the player iterators ready for a new turn.
     */
    public void resetIterators() {
        nextActiveUnitIterator.reset();
        nextGoingToUnitIterator.reset();
        nextTradeRouteUnitIterator.reset();
    }

    /**
     * Get the trade routes defined for this player.
     *
     * @return The list of <code>TradeRoute</code>s for this player.
     */
    public final List<TradeRoute> getTradeRoutes() {
        return tradeRoutes;
    }

    /**
     * Get a trade route by name.
     *
     * @param name The trade route name.
     */
    public TradeRoute getTradeRoute(String name) {
        for (TradeRoute t : tradeRoutes) {
            if (t.getName().equals(name)) return t;
        }
        return null;
    }

    /**
     * Set the players trade routes.
     *
     * @param newTradeRoutes The new list of <code>TradeRoute</code>s.
     */
    public final void setTradeRoutes(final List<TradeRoute> newTradeRoutes) {
        tradeRoutes.clear();
        tradeRoutes.addAll(newTradeRoutes);
    }

    /**
     * Get a unique name for a new trade route.
     */
    public String getNewTradeRouteName() {
        String base = Messages.message("tradeRoute.newRoute");
        if (getTradeRoute(base) == null) return base;
        String name;
        int i = 1;
        while (getTradeRoute(name = base + Integer.toString(i)) != null) {
            i++;
        }
        return name;
    }

    /**
     * Add an ownable to a caching container.  Not all ownables are
     * cached.
     *
     * @param o The <code>Ownable</code> to add.
     * @return True if the container changed.
     */
    public boolean addOwnable(Ownable o) {
        return (o instanceof Settlement) ? addSettlement((Settlement)o)
            : (o instanceof Unit) ? addUnit((Unit)o)
            : false;
    }

    /**
     * Remove an ownable from a caching container.  Not all ownables
     * are cached.
     *
     * @param o The <code>Ownable</code> to remove.
     * @return True if the container changed.
     */
    public boolean removeOwnable(Ownable o) {
        return (o instanceof Settlement) ? removeSettlement((Settlement)o)
            : (o instanceof Unit) ? removeUnit((Unit)o)
            : false;
    }


    //
    // Settlements
    //

    /**
     * Gets a the settlements this player owns.
     *
     * @return The list of <code>Settlements</code> this player owns.
     */
    public List<Settlement> getSettlements() {
        return settlements;
    }

    /**
     * Get the number of settlements.
     *
     * @return The number of settlements this player has.
     */
    public int getNumberOfSettlements() {
        return settlements.size();
    }

    /**
     * Get the number of port settlements.
     *
     * @return The number of port settlements this player has.
     */
    public int getNumberOfPorts() {
        if (!isEuropean()) return 0;
        int n = 0;
        for (Colony colony : getColonies()) {
            if (colony.isConnectedPort()) n++;
        }
        return n;
    }

    /**
     * Does this player own a given settlement.
     *
     * @param settlement The <code>Settlement</code> to check.
     * @return True if this <code>Player</code> owns the given
     *     <code>Settlement</code>.
     */
    public boolean hasSettlement(Settlement settlement) {
        return settlements.contains(settlement);
    }

    /**
     * Adds a given settlement to this player's list of settlements.
     *
     * @param settlement The <code>Settlement</code> to add.
     * @return True if the settlements container changed.
     */
    public boolean addSettlement(Settlement settlement) {
        if (settlement == null) return false;
        if (!owns(settlement)) {
            throw new IllegalStateException("Does not own: " + settlement);
        }
        if (hasSettlement(settlement)) return false;
        settlements.add(settlement);
        return true;
    }

    /**
     * Removes the given settlement from this player's list of settlements.
     *
     * @param settlement The <code>Settlement</code> to remove.
     * @return True if the settlements container changed.
     */
    public boolean removeSettlement(Settlement settlement) {
        return settlements.remove(settlement);
    }

    /**
     * Gets the sum of units currently working in the colonies of this
     * player.
     *
     * @return The sum of the units currently working in the colonies.
     */
    public int getColoniesPopulation() {
        int i = 0;
        for (Colony c : getColonies()) i += c.getUnitCount();
        return i;
    }

    /**
     * Gets the <code>Colony</code> with the given name.
     *
     * @param name The name of the <code>Colony</code>.
     * @return The <code>Colony</code> with the given name, or null if
     *     not found.
     */
    public Colony getColonyByName(String name) {
        for (Colony colony : getColonies()) {
            if (colony.getName().equals(name)) return colony;
        }
        return null;
    }

    /**
     * Gets the <code>IndianSettlement</code> with the given name.
     *
     * @param name The name of the <code>IndianSettlement</code>.
     * @return The <code>IndianSettlement</code> with the given name,
     *     or null if not found.
     */
    public IndianSettlement getIndianSettlementByName(String name) {
        for (IndianSettlement settlement : getIndianSettlements()) {
            if (settlement.getName().equals(name)) return settlement;
        }
        return null;
    }

    /**
     * Gets a fresh list of all colonies this player owns.
     * It is an error to call this on non-European players.
     *
     * @return A fresh list of the <code>Colony</code>s this player owns.
     */
    public List<Colony> getColonies() {
        List<Colony> colonies = new ArrayList<Colony>();
        for (Settlement s : getSettlements()) {
            if (s instanceof Colony) {
                colonies.add((Colony)s);
            } else {
                throw new RuntimeException("getColonies found: " + s);
            }
        }
        return colonies;
    }

    /**
     * Get a sorted list of all colonies this player owns.
     *
     * @param c A comparator to operate on the colony list.
     * @return A fresh list of the <code>Colony</code>s this player owns.
     */
    public List<Colony> getSortedColonies(Comparator<Colony> c) {
        List<Colony> colonies = getColonies();
        Collections.sort(colonies, c);
        return colonies;
    }

    /**
     * Gets a list of all the IndianSettlements this player owns.
     * It is an error to call this on non-native players.
     *
     * @return The indian settlements this player owns.
     */
    public List<IndianSettlement> getIndianSettlements() {
        List<IndianSettlement> indianSettlements
            = new ArrayList<IndianSettlement>();
        for (Settlement s : getSettlements()) {
            if (s instanceof IndianSettlement) {
                indianSettlements.add((IndianSettlement)s);
            } else {
                throw new RuntimeException("getIndianSettlements found: " + s);
            }
        }
        return indianSettlements;
    }

    /**
     * Find a <code>Settlement</code> by name.
     *
     * @param name The name of the <code>Settlement</code>.
     * @return The <code>Settlement</code>, or <code>null</code> if not found.
     **/
    public Settlement getSettlementByName(String name) {
        return (isIndian()) ? getIndianSettlementByName(name)
            : getColonyByName(name);
    }

    /**
     * Gets the port closest to Europe owned by this player.
     *
     * @return This players closest port.
     */
    public Settlement getClosestPortForEurope() {
        int bestValue = INFINITY;
        Settlement best = null;
        for (Settlement settlement : getSettlements()) {
            int value = settlement.getHighSeasCount();
            if (bestValue > value) {
                bestValue = value;
                best = settlement;
            }
        }
        return best;
    }


    //
    // Messages and history
    //

    /**
     * Gets all the model messages for this player.
     *
     * @return all The <code>ModelMessage</code>s for this
     *     <code>Player</code>.
     */
    public List<ModelMessage> getModelMessages() {
        return modelMessages;
    }

    /**
     * Gets all new messages for this player.
     *
     * @return all The new <code>ModelMessage</code>s for this
     *     <code>Player</code>.
     */
    public List<ModelMessage> getNewModelMessages() {
        List<ModelMessage> out = new ArrayList<ModelMessage>();
        for (ModelMessage message : modelMessages) {
            if (message.hasBeenDisplayed()) continue;
            out.add(message); // preserve message order
        }
        return out;
    }

    /**
     * Adds a message for this player.
     *
     * @param modelMessage The <code>ModelMessage</code> to add.
     */
    public void addModelMessage(ModelMessage modelMessage) {
        modelMessages.add(modelMessage);
    }

    /**
     * Refilters the current model messages, removing the ones that
     * are no longer valid.
     *
     * @param options The <code>OptionGroup</code> for message display
     *     to enforce.
     */
    public void refilterModelMessages(OptionGroup options) {
        Iterator<ModelMessage> messageIterator = modelMessages.iterator();
        while (messageIterator.hasNext()) {
            ModelMessage message = messageIterator.next();
            String id = message.getMessageType().getOptionName();
            if (!options.getBoolean(id)) messageIterator.remove();
        }
    }

    /**
     * Removes all undisplayed model messages for this player.
     */
    public void removeDisplayedModelMessages() {
        Iterator<ModelMessage> messageIterator = modelMessages.iterator();
        while (messageIterator.hasNext()) {
            ModelMessage message = messageIterator.next();
            if (message.hasBeenDisplayed()) messageIterator.remove();
        }
    }

    /**
     * Removes all the model messages for this player.
     */
    public void clearModelMessages() {
        modelMessages.clear();
    }

    /**
     * Sometimes an event causes the source (and display) fields in an
     * accumulated model message to become invalid (e.g. Europe disappears
     * on independence).  This routine is for cleaning up such cases.
     *
     * @param source The source field that has become invalid.
     * @param newSource A new source field to replace the old with, or
     *     if null then remove the message
     */
    public void divertModelMessages(FreeColGameObject source,
                                    FreeColGameObject newSource) {
        Iterator<ModelMessage> messageIterator = modelMessages.iterator();
        while (messageIterator.hasNext()) {
            ModelMessage message = messageIterator.next();
            if (message.getSourceId() == source.getId()) {
                if (newSource == null) {
                    messageIterator.remove();
                } else {
                    message.divert(newSource);
                }
            }
        }
    }

    /**
     * Get the history events for this player.
     *
     * @return The list of <code>HistoryEvent</code>s for this player.
     */
    public final List<HistoryEvent> getHistory() {
        return history;
    }

    /**
     * Add a history event to this player.
     *
     * @param event The <code>HistoryEvent</code> to add.
     */
    public void addHistory(HistoryEvent event) {
        history.add(event);
    }


    //
    // The players view of the Map
    //

    /**
     * Gets the default initial location where the units arriving from
     * {@link Europe} appear on the map.
     *
     * @return The entry <code>Location</code>.
     * @see Unit#getEntryLocation
     */
    public Location getEntryLocation() {
        return entryLocation;
    }

    /**
     * Sets the default initial location where the units arriving from
     * {@link Europe} appear on the map.
     *
     * @param entryLocation The new entry <code>Location</code>.
     * @see #getEntryLocation
     */
    public void setEntryLocation(Location entryLocation) {
        this.entryLocation = entryLocation;
    }

    /**
     * Get the players high seas.
     *
     * @return The <code>HighSeas</code> for this player.
     */
    public final HighSeas getHighSeas() {
        return highSeas;
    }

    /**
     * Initialize the highSeas.
     * Needs to be public until the backward compatibility code in
     * FreeColServer is gone.
     */
    public void initializeHighSeas() {
        Game game = getGame();
        highSeas = new HighSeas(game);
        if (europe != null) highSeas.addDestination(europe);
        if (game.getMap() != null ) highSeas.addDestination(game.getMap());
    }

    /**
     * Can this player see a given tile.
     *
     * The tile can be seen if it is in a unit or settlement's line of sight.
     *
     * @param tile The <code>Tile</code> to check.
     * @return True if this player can see the given <code>Tile</code>.
     */
    public boolean canSee(Tile tile) {
        if (tile == null) return false;

        do {
            synchronized (canSeeLock) {
                if (canSeeValid) {
                    return canSeeTiles[tile.getX()][tile.getY()];
                }
            }
        } while (resetCanSeeTiles());
        return false;
    }

    /**
     * Forces an update of the <code>canSeeTiles</code>.
     *
     * This method should be used to invalidate the current
     * <code>canSeeTiles</code> when something significant changes.
     * The method {@link #resetCanSeeTiles} will be called whenever it
     * is needed.
     *
     * So what is "significant"?  Looking at the makeCanSeeTiles routine
     * suggests the following:
     *
     * - Unit added to map
     * - Unit removed from map
     * - Unit moved on map
     * - Unit type changes (which may change its line-of-sight)
     * - Unit ownership changes
     * - Settlement added to map
     * - Settlement removed from map
     * - Settlement ownership changes
     * - Coronado added (can now see other colonies)
     * - Coronado removed (only in debug mode)
     * - Mission established (if enhanced missionaries enabled)
     * - Mission removed (if enhanced missionaries enabled)
     * - Mission ownership changes (Spanish succession with enhanced
     *                              missionaries enabled)
     * - Map is unexplored (debug mode)
     *
     * Ideally then when any of these events occurs we should call
     * invalidateCanSeeTiles().  However while iCST is quick and
     * cheap, as soon as we then call canSee() the big expensive
     * makeCanSeeTiles will be run.  Often the situation in the server
     * is that several routines with visibility implications will be
     * called in succession.  Usually there, the best solution is to
     * make all the changes and issue the iCST at the end.  So, to
     * make this a bit more visible, routines that change visibility
     * are annotated with a "-vis" comment at both definition and call
     * sites.  Similarly routines that fix up the mess have a "+vis"
     * comment.  Thus it is an error for a -vis to appear without a
     * following +vis (unless the enclosing routine is marked -vis).
     * By convention, we try to avoid cs* routines being -vis.
     */
    public void invalidateCanSeeTiles() {
        synchronized (canSeeLock) {
            canSeeValid = false;
        }
    }

    /**
     * Resets this player's "can see"-tiles.  This is done by setting
     * all the tiles within each {@link Unit} and {@link Settlement}s
     * line of sight visible.  The other tiles are made invisible.
     *
     * Use {@link #invalidateCanSeeTiles} whenever possible.
     *
     * @return True if successful.
     */
    private boolean resetCanSeeTiles() {
        Map map = getGame().getMap();
        if (map == null) return false;

        boolean[][] cST = makeCanSeeTiles(map);
        synchronized (canSeeLock) {
            canSeeTiles = cST;
            canSeeValid = true;
        }
        return true;
    }

    /**
     * Checks if this player has explored the given tile.
     *
     * @param tile The <code>Tile</code> to check.
     * @return True if the <code>Tile</code> has been explored.
     */
    public boolean hasExplored(Tile tile) {
        return tile.isExplored();
    }

    /**
     * Builds a canSeeTiles array.
     *
     * Note that tiles must be tested for null as they may be both
     * valid tiles but yet null during a save game load.
     *
     * Note the use of copies of the unit and settlement lists to
     * avoid nasty surprises due to asynchronous disappearance of
     * members of either.  TODO: see if this can be relaxed.
     *
     * @param map The <code>Map</code> to use.
     * @return A canSeeTiles array.
     */
    private boolean[][] makeCanSeeTiles(Map map) {
        final Specification spec = getSpecification();
        // Simple case when there is no fog of war: a tile is
        // visible once it is explored.
        if (!spec.getBoolean(GameOptions.FOG_OF_WAR)) {
            boolean[][] cST = (canSeeTiles != null) ? canSeeTiles
                : new boolean[map.getWidth()][map.getHeight()];
            for (Tile t : getGame().getMap().getAllTiles()) {
                if (t != null) {
                    cST[t.getX()][t.getY()] = hasExplored(t);
                }
            }
            return cST;
        }

        // When there is fog, have to trace all locations where the
        // player has units, settlements, (optionally) missions, and
        // extra visibility.
        // Set the PET for visible tiles to the tile itself.
        boolean[][] cST = new boolean[map.getWidth()][map.getHeight()];

        for (Unit unit : getUnits()) {
            // Only consider units directly on the map, not those on a
            // carrier or in Europe.
            if (!(unit.getLocation() instanceof Tile)) continue;

            // All the units.
            for (Tile t : ((Tile)unit.getLocation()).getSurroundingTiles(0,
                    unit.getLineOfSight())) {
                cST[t.getX()][t.getY()] = true;
                t.seeTile(this);
            }
        }
        // All the settlements.
        for (Settlement settlement : getSettlements()) {
            for (Tile t : settlement.getTile().getSurroundingTiles(0,
                    settlement.getLineOfSight())) {
                cST[t.getX()][t.getY()] = true;
                t.seeTile(this);
            }
        }
        // All missions if using enhanced missionaries.
        if (isEuropean()
                && spec.getBoolean(GameOptions.ENHANCED_MISSIONARIES)) {
            for (Player other : getGame().getPlayers()) {
                if (this.equals(other) || !other.isIndian()) {
                    continue;
                }
                for (IndianSettlement is : other.getIndianSettlements()) {
                    if (!is.hasMissionary(this)) {
                        continue;
                    }
                    for (Tile t : is.getTile().getSurroundingTiles(0,
                            is.getLineOfSight())) {
                        cST[t.getX()][t.getY()] = true;
                        t.seeTile(this);
                    }
                }
            }
        }
        // All other European settlements if can see all colonies.
        if (isEuropean() && hasAbility(Ability.SEE_ALL_COLONIES)) {
            for (Player other : getGame().getPlayers()) {
                if (this.equals(other) || !other.isEuropean()) {
                    continue;
                }
                for (Colony colony : other.getColonies()) {
                    for (Tile t : colony.getTile().getSurroundingTiles(0,
                            colony.getLineOfSight())) {
                        cST[t.getX()][t.getY()] = true;
                        t.seeTile(this);
                    }
                }
            }
        }
        return cST;
    }


    //
    // Foreign relations
    //

    /**
     * Gets the hostility this player has against the given player.
     *
     * @param player The other <code>Player</code>.
     * @return An object representing the tension level.
     */
    public Tension getTension(Player player) {
        if (player == null) throw new IllegalStateException("Null player.");
        Tension newTension = tension.get(player);
        if (newTension == null) {
            newTension = new Tension(Tension.TENSION_MIN);
            tension.put(player, newTension);
        }
        return newTension;
    }

    /**
     * Sets the tension with respect to a given player.
     *
     * @param player The other <code>Player</code>.
     * @param newTension The new <code>Tension</code>.
     */
    public void setTension(Player player, Tension newTension) {
        if (player == this || player == null) return;
        tension.put(player, newTension);
    }

    /**
     * Removes all tension with respect to a given player.  Used when a
     * player leaves the game.
     *
     * @param player The <code>Player</code> to remove tension for.
     */
    public void removeTension(Player player) {
        if (player != null) tension.remove(player);
    }

    /**
     * Gets the stance towards a given player.
     *
     * @param player The other <code>Player</code> to check.
     * @return The stance.
     */
    public Stance getStance(Player player) {
        return (player == null || stance.get(player.getId()) == null)
            ? Stance.UNCONTACTED
            : stance.get(player.getId());
    }

    /**
     * Sets the stance towards a given player.
     *
     * @param player The <code>Player</code> to set the
     *     <code>Stance</code> for.
     * @param newStance The new <code>Stance</code>.
     * @return True if the stance change was valid.
     * @throws IllegalArgumentException if player is null or this.
     */
    public boolean setStance(Player player, Stance newStance) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be 'null'.");
        }
        if (player == this) {
            throw new IllegalArgumentException("Cannot set the stance towards ourselves.");
        }
        if (newStance == null) {
            stance.remove(player.getId());
            return true;
        }
        Stance oldStance = stance.get(player.getId());
        if (newStance.equals(oldStance)) return true;

        boolean valid = true;;
        if ((newStance == Stance.CEASE_FIRE && oldStance != Stance.WAR)
            || newStance == Stance.UNCONTACTED) {
            valid = false;
        }
        stance.put(player.getId(), newStance);
        return valid;
    }

    /**
     * Is this player at war with the specified one.
     *
     * @param player The other <code>Player</code> to check.
     * @return True if the players are at war.
     */
    public boolean atWarWith(Player player) {
        return getStance(player) == Stance.WAR;
    }

    /**
     * Checks whether this player is at war with any other player.
     *
     * @return True if this player is at war with any other.
     */
    public boolean isAtWar() {
        for (Player player : getGame().getPlayers()) {
            if (atWarWith(player)) return true;
        }
        return false;
    }

    /**
     * Has this player met contacted the given one?
     *
     * @param player The other <code>Player</code> to check.
     * @return True if this <code>Player</code> has contacted the other.
     */
    public boolean hasContacted(Player player) {
        return getStance(player) != Stance.UNCONTACTED;
    }

    /**
     * Has this player has met with any Europeans at all?
     *
     * @return True if this <code>Player</code> has contacted any Europeans.
     */
    public boolean hasContactedEuropeans() {
        for (Player other : getGame().getLiveEuropeanPlayers()) {
            if (other != this && hasContacted(other)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Has this player met any natives at all?
     *
     * @return True if this <code>Player</code> has contacted any natives.
     */
    public boolean hasContactedIndians() {
        for (Player other : getGame().getPlayers()) {
            if (other != this && !other.isDead() && other.isIndian()
                && hasContacted(other)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set this player as having made initial contact with another player.
     * Always start with PEACE, which can go downhill fast.
     *
     * @param player1 One <code>Player</code> to check.
     * @param player2 The other <code>Player</code> to check.
     */
    public static void makeContact(Player player1, Player player2) {
        player1.stance.put(player2.getId(), Stance.PEACE);
        player2.stance.put(player1.getId(), Stance.PEACE);
        player1.setTension(player2, new Tension(Tension.TENSION_MIN));
        player2.setTension(player1, new Tension(Tension.TENSION_MIN));
    }

    /**
     * Gets the price of the given land.
     *
     * @param tile The <code>Tile</code> to get the price for.
     * @return The price of the land if it is for sale, zero if it is
     *     already ours, unclaimed or unwanted, negative if it is not
     *     for sale.
     */
    public int getLandPrice(Tile tile) {
        final Specification spec = getSpecification();
        Player nationOwner = tile.getOwner();
        int price = 0;

        if (nationOwner == null || nationOwner == this) {
            return 0; // Freely available
        } else if (tile.hasSettlement()) {
            return -1; // Not for sale
        } else if (nationOwner.isEuropean()) {
            if (tile.getOwningSettlement() != null
                && tile.getOwningSettlement().getOwner() == nationOwner) {
                return -1; // Nailed down by a European colony
            } else {
                return 0; // Claim abandoned or only by tile improvement
            }
        } // Else, native ownership
        for (GoodsType type : spec.getGoodsTypeList()) {
            if (type == spec.getPrimaryFoodType()) {
                // Only consider specific food types, not the aggregation.
                continue;
            }
            price += tile.potential(type, null);
        }
        price *= spec.getInteger(GameOptions.LAND_PRICE_FACTOR);
        price += 100;
        return (int) applyModifier(price, Modifier.LAND_PAYMENT_MODIFIER,
                                   null, getGame().getTurn());
    }


    //
    // Claiming of tiles
    //

    /**
     * A variety of reasons why a tile can not be claimed, either
     * to found a settlement or just to be used by one, including the
     * double negative NONE == "no reason" case.
     */
    public static enum NoClaimReason {
        NONE,            // Actually, tile can be claimed
        TERRAIN,         // Not on settleable terrain
        RUMOUR,          // Europeans can not claim tiles with LCR
        WATER,           // Natives do not claim water
        OCCUPIED,        // Hostile unit present.
        SETTLEMENT,      // Settlement present
        WORKED,          // One of our settlements is working this tile
        EUROPEANS,       // Owned by Europeans and not for sale
        NATIVES,         // Owned by natives and they want payment for it
    };

    /**
     * Can a tile be owned by this player?
     *
     * @param tile The <code>Tile</code> to consider.
     * @return True if the tile can be owned by this player.
     */
    public boolean canOwnTile(Tile tile) {
        return canOwnTileReason(tile) == NoClaimReason.NONE;
    }

    /**
     * Can a tile be owned by this player?
     * This is a test of basic practicality and does not consider
     * the full complexity of tile ownership issues.
     *
     * @param tile The <code>Tile</code> to consider.
     * @return The reason why/not the tile can be owned by this player.
     */
    private NoClaimReason canOwnTileReason(Tile tile) {
        for (Unit u : tile.getUnitList()) {
            Player owner = u.getOwner();
            if (owner == this || !owner.atWarWith(this)) break; // Not hostile
            // If the unit is military, the tile is held against us.
            if (u.isOffensiveUnit()) return NoClaimReason.OCCUPIED;
        }
        return (isEuropean())
            ? ((tile.hasLostCityRumour())
                ? NoClaimReason.RUMOUR
                : NoClaimReason.NONE)
            : ((tile.isLand())
                ? NoClaimReason.NONE
                : NoClaimReason.WATER);
    }

    /**
     * Checks if a tile can be claimed for use by a settlement.
     *
     * @param tile The <code>Tile</code> to try to claim.
     * @return True if the tile can be claimed to found a settlement.
     */
    public boolean canClaimForSettlement(Tile tile) {
        return canClaimForSettlementReason(tile) == NoClaimReason.NONE;
    }

    /**
     * The test for whether a tile can be freely claimed by a player
     * settlement (freely => not by purchase or stealing).  The rule
     * for the center tile is different, see below.
     *
     * The tile must be ownable by this player, settlement-free, and
     * either not currently owned, owned by this player and not by
     * another settlement that is using the tile, or owned by someone
     * else who does not want anything for it.  Got that?
     *
     * @param tile The <code>Tile</code> to try to claim.
     * @return The reason why/not the tile can be claimed.
     */
    public NoClaimReason canClaimForSettlementReason(Tile tile) {
        int price;
        NoClaimReason reason = canOwnTileReason(tile);
        return (reason != NoClaimReason.NONE) ? reason
            : (tile.hasSettlement()) ? NoClaimReason.SETTLEMENT
            : (tile.getOwner() == null) ? NoClaimReason.NONE
            : (tile.getOwner() == this) ? ((tile.isInUse())
                                           ? NoClaimReason.WORKED
                                           : NoClaimReason.NONE)
            : ((price = getLandPrice(tile)) < 0) ? NoClaimReason.EUROPEANS
            : (price > 0) ? NoClaimReason.NATIVES
            : NoClaimReason.NONE;
    }

    /**
     * Can a tile be claimed to found a settlement on?
     *
     * @param tile The <code>Tile</code> to try to claim.
     * @return True if the tile can be claimed to found a settlement.
     */
    public boolean canClaimToFoundSettlement(Tile tile) {
        return canClaimToFoundSettlementReason(tile) == NoClaimReason.NONE;
    }

    /**
     * Can a tile be claimed to found a settlement on?
     * Almost the same as canClaimForSettlement but there is an extra
     * requirement that the tile be of a settleable type, and some
     * relaxations that allow free center tile acquisition
     *
     * @param tile The <code>Tile</code> to try to claim.
     * @return The reason why/not the tile can be claimed.
     */
    public NoClaimReason canClaimToFoundSettlementReason(Tile tile) {
        NoClaimReason reason;
        return (!tile.getType().canSettle()) ? NoClaimReason.TERRAIN
            : ((reason = canClaimForSettlementReason(tile))
               != NoClaimReason.NATIVES) ? reason
            : (canClaimFreeCenterTile(tile)) ? NoClaimReason.NONE
            : NoClaimReason.NATIVES;
    }

    /**
     * Is this tile claimable for a colony center tile under the
     * special provisions of the model.option.buildOnNativeLand option.
     *
     * @param tile The <code>Tile</code> to try to claim.
     * @return True if the tile can be claimed.
     */
    private boolean canClaimFreeCenterTile(Tile tile) {
        final Specification spec = getGame().getSpecification();
        String build = spec.getString(GameOptions.BUILD_ON_NATIVE_LAND);
        return isEuropean()
            && tile.getOwner() != null
            && tile.getOwner().isIndian()
            && (GameOptions.BUILD_ON_NATIVE_LAND_ALWAYS.equals(build)
                || (GameOptions.BUILD_ON_NATIVE_LAND_FIRST.equals(build)
                    && hasZeroSettlements())
                || (GameOptions.BUILD_ON_NATIVE_LAND_FIRST_AND_UNCONTACTED.equals(build)
                    && hasZeroSettlements()
                    && (tile.getOwner() == null
                        || tile.getOwner().getStance(this)
                        == Stance.UNCONTACTED)));
    }

    /**
     * The second and third cases of buildOnNative land need to test
     * if the player has no settlements yet.  We can not just check
     * that the number of settlement is zero because by the time the
     * settlement is being placed and we are collecting the tiles to
     * claim, the settlement already exists and thus there will
     * already be one settlement--- so we have to check if that one
     * settlement is on the map yet.
     *
     * @return True if the player has no settlements (on the map) yet.
     */
    private boolean hasZeroSettlements() {
        List<Settlement> settlements = getSettlements();
        return settlements.isEmpty()
            || (settlements.size() == 1
                && settlements.get(0).getTile().getSettlement() == null);
    }

    /**
     * Can the ownership of this tile be claimed for the purposes of
     * making an improvement.  Quick test that does not handle the
     * curly case of tile transfer between colonies, or guarantee
     * success (natives may want to be paid), but just that success is
     * possible.
     *
     * @param tile The <code>Tile</code> to consider.
     *
     * @return True if the tile ownership can be claimed.
     */
    public boolean canClaimForImprovement(Tile tile) {
        Player owner = tile.getOwner();
        return owner == null || owner == this || getLandPrice(tile) == 0;
    }

    /**
     * Can a tile be acquired from its owners and used for an improvement?
     * Slightly weakens canClaimForImprovement to allow for purchase
     * and/or stealing.
     *
     * @param tile The <code>Tile</code> to consider.
     * @return True if the tile ownership can be claimed.
     */
    public boolean canAcquireForImprovement(Tile tile) {
        return canClaimForImprovement(tile)
            || getLandPrice(tile) > 0;
    }

    /**
     * Gets the list of tiles that might be claimable by a settlement.
     * We can not do a simple iteration of the rings because this
     * allows settlements to claim tiles across unclaimable gaps
     * (e.g. Aztecs owning tiles on nearby islands).  So we have to
     * only allow tiles that are adjacent to a known connected tile.
     *
     * @param centerTile The intended settlement center <code>Tile</code>.
     * @param radius The radius of the settlement.
     * @return A list of potentially claimable tiles.
     */
    public List<Tile> getClaimableTiles(Tile centerTile, int radius) {
        List<Tile> tiles = new ArrayList<Tile>();
        List<Tile> layer = new ArrayList<Tile>();
        if (canClaimToFoundSettlement(centerTile)) {
            layer.add(centerTile);
            for (int r = 1; r <= radius; r++) {
                List<Tile> lastLayer = new ArrayList<Tile>(layer);
                tiles.addAll(layer);
                layer.clear();
                for (Tile have : lastLayer) {
                    for (Tile next : have.getSurroundingTiles(1)) {
                        if (!tiles.contains(next)
                            && canClaimForSettlement(next)) {
                            layer.add(next);
                        }
                    }
                }
            }
            tiles.addAll(layer);
        }
        return tiles;
    }


    //
    // AI helpers for evaluation settlement locations
    //

    /**
     * Not currently in use.  Leave here for now, it might yet be revived.
     *
     * Calculates the value of an outpost-type colony at this tile.
     * An "outpost" is supposed to be a colony containing one worker, exporting
     * its whole production to europe. The value of such colony is the maximum
     * amount of money it can make in one turn, assuming sale of its secondary
     * goods plus farmed goods from one of the surrounding tiles.
     *
     * @return The value of a future colony located on this tile. This value is
     *         used by the AI when deciding where to build a new colony.
    public int getOutpostValue(Tile t) {
        Market market = getMarket();
        if (canClaimToFoundSettlement(t)) {
            boolean nearbyTileIsOcean = false;
            float advantages = 1f;
            int value = 0;
            for (Tile tile : t.getSurroundingTiles(1)) {
                if (tile.getColony() != null) {
                    // can't build next to colony
                    return 0;
                } else if (tile.hasSettlement()) {
                    // can build next to an indian settlement, but shouldn't
                    SettlementType type = tile.getSettlement().getType();
                    if (type.getClaimableRadius() > 1) {
                        // really shouldn't build next to cities
                        advantages *= 0.25f;
                    } else {
                        advantages *= 0.5f;
                    }
                } else {
                    if (tile.isHighSeasConnected()) {
                        nearbyTileIsOcean = true;
                    }
                    if (tile.getType()!=null) {
                        for (AbstractGoods production : tile.getType().getProduction()) {
                            GoodsType type = production.getType();
                            int potential = market.getSalePrice(type, tile.potential(type, null));
                            if (tile.getOwner() != null &&
                                !this.owns(tile)) {
                                // tile is already owned by someone (and not by us!)
                                if (tile.getOwner().isEuropean()) {
                                    continue;
                                } else {
                                    potential /= 2;
                                }
                            }
                            value = Math.max(value, potential);
                        }
                    }
                }
            }

            // add good that could be produced by a colony on this tile
            int bestValue = 0;
            for (ProductionType productionType : t.getType()
                     .getProductionTypes(true)) {
                if (productionType.getOutputs() != null) {
                    int newValue = 0;
                    for (AbstractGoods output: productionType.getOutputs()) {
                        newValue += market.getSalePrice(output.getType(),
                                                        t.potential(output.getType(), null));
                    }
                    if (newValue > bestValue) {
                        bestValue = newValue;
                    }
                }
            }
            value += bestValue;
            if (nearbyTileIsOcean) {
                return Math.max(0, (int) (value * advantages));
            }
        }
        return 0;
    }
    */

    /**
     * Gets a list of values for building a <code>Colony</code> on the
     * given tile for each <code>ColonyValueCategory</code>.
     *
     * TODO: tune magic numbers.  Expose more to the spec?
     *
     * @param tile The <code>Tile</code>
     * @return A list of values.
     */
    public List<Double> getAllColonyValues(Tile tile) {
        // Want a few settlements before taking risks
        final int LOW_SETTLEMENT_NUMBER = 3;

        // Would like a caravel to reach high seas in 3 moves
        final int LONG_PATH_TILES = 12;

        // Applied once
        final double MOD_HAS_RESOURCE           = 0.75;
        final double MOD_FOOD_LOW               = 0.75;
        final double MOD_INITIAL_FOOD           = 2.0;
        final double MOD_STEAL                  = 0.5;
        final double MOD_INLAND                 = 0.5;

        // Applied per surrounding tile
        final double MOD_OWNED_EUROPEAN         = 0.67;
        final double MOD_OWNED_NATIVE           = 0.8;

        // Applied per goods production, per surrounding tile
        final double MOD_HIGH_PRODUCTION        = 1.2;
        final double MOD_GOOD_PRODUCTION        = 1.1;

        // Applied per occurrence (own colony only one-time), range-dependent.
        final int DISTANCE_MAX = 5;
        final double[] MOD_OWN_COLONY     = {0.0, 0.0, 0.5, 1.50, 1.25};
        final double[] MOD_ENEMY_COLONY   = {0.0, 0.0, 0.4, 0.50, 0.70};
        final double[] MOD_NEUTRAL_COLONY = {0.0, 0.0, 0.7, 0.80, 1.00};
        final double[] MOD_ENEMY_UNIT     = {0.4, 0.5, 0.6, 0.75, 0.90};

        // Goods production in excess of this on a tile counts as good/high
        final int GOOD_PRODUCTION = 4;
        final int HIGH_PRODUCTION = 8;

        // Counting "high" production as 2, "good" production as 1
        // overall food production is considered low/very low if less than...
        final int FOOD_LOW = 4;
        final int FOOD_VERY_LOW = 1;

        // Multiplicative modifiers, to be applied to value later
        List<Double> values = new ArrayList<Double>();
        for (ColonyValueCategory c : ColonyValueCategory.values()) {
            values.add(1.0);
        }
        // Penalize certain problems more in the initial colonies.
        double development = Math.min(LOW_SETTLEMENT_NUMBER, settlements.size())
                / (double)LOW_SETTLEMENT_NUMBER;
        int portCount = getNumberOfPorts();

        if (tile.isPolar() && settlements.size() < LOW_SETTLEMENT_NUMBER) {
            values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                       NoValueType.POLAR.getDouble());
            return values;
        }

        switch (canClaimToFoundSettlementReason(tile)) {
        case NONE:
            break;
        case TERRAIN: case WATER:
            values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                       NoValueType.TERRAIN.getDouble());
            return values;
        case RUMOUR:
            if (settlements.isEmpty()) {
                values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                           NoValueType.RUMOUR.getDouble());
                return values;
            }
            values.set(ColonyValueCategory.A_TILE.ordinal(),
                       development);
            break;
        case OCCUPIED: // transient we hope
            values.set(ColonyValueCategory.A_TILE.ordinal(),
                       MOD_ENEMY_UNIT[0]);
            break;
        case SETTLEMENT: case WORKED: case EUROPEANS:
            values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                       NoValueType.SETTLED.getDouble());
            return values;
        case NATIVES: // If we have no ports, we are desperate enough to steal
            if (tile.getOwningSettlement() != null
                && tile.getOwningSettlement().getTile() != null
                && tile.getOwningSettlement().getTile().isAdjacent(tile)) {
                values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                           NoValueType.SETTLED.getDouble());
                return values;
            }
            int price = getLandPrice(tile);
            if (price > 0 && !checkGold(price) && portCount > 0) {
                values.set(ColonyValueCategory.A_TILE.ordinal(),
                           MOD_STEAL);
            }
            break;
        default:
            values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                       NoValueType.BOGUS.getDouble());
            return values;
        }

        // Set up maps for all foods and building materials
        final Specification spec = getSpecification();
        TypeCountMap<GoodsType> production = new TypeCountMap<GoodsType>();

        // Initialize tile value with food production.
        int initialFood = 0;
        final GoodsType foodType = spec.getPrimaryFoodType();
        for (ProductionType productionType : tile.getType()
                 .getProductionTypes(true)) {
            if (productionType.getOutputs() != null) {
                for (AbstractGoods output : productionType.getOutputs()) {
                    if (!output.getType().isFoodType()) continue;
                    int amount = tile.potential(output.getType(), null);
                    if (amount > initialFood) initialFood = amount;
                }
            }
        }
        if (initialFood <= FOOD_VERY_LOW) {
            values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                       NoValueType.FOOD.getDouble());
            return values;
        }
        production.incrementCount(foodType, initialFood);
        values.set(ColonyValueCategory.A_PROD.ordinal(),
                   (double)initialFood * foodType.getProductionWeight());

        // Penalty if there is no direct connection to the high seas, or
        // if it is too long.
        int tilesToHighSeas = tile.getHighSeasCount();
        if (tilesToHighSeas < 0) {
            if (portCount < LOW_SETTLEMENT_NUMBER) {
                values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                           NoValueType.INLAND.getDouble());
                return values;
            }
            values.set(ColonyValueCategory.A_EUROPE.ordinal(),
                       MOD_INLAND);
        } else if (tilesToHighSeas >= LONG_PATH_TILES) {
            // Normally penalize in direct proportion to length of
            // path, but scale up to penalizing by the square of the
            // path length for the first colony.
            double trip = (double)LONG_PATH_TILES / tilesToHighSeas;
            values.set(ColonyValueCategory.A_EUROPE.ordinal(),
                       Math.pow(trip, 2.0 - development));
        } else {
            values.set(ColonyValueCategory.A_EUROPE.ordinal(),
                       1.0 + 0.25 * ((double)LONG_PATH_TILES
                           / (LONG_PATH_TILES - tilesToHighSeas)));
        }

        // Penalty for building on a resource tile, because production
        // can not be improved much.
        values.set(ColonyValueCategory.A_RESOURCE.ordinal(),
                   (tile.hasResource()) ? MOD_HAS_RESOURCE : 1.0);

        Set<GoodsType> highProduction = new HashSet<GoodsType>();
        Set<GoodsType> goodProduction = new HashSet<GoodsType>();
        for (Tile t : tile.getSurroundingTiles(1)) {
            if (t.getType() == null) continue; // Unexplored!?!
            if (t.getSettlement() != null) { // Should not happen, tested above
                values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                           NoValueType.SETTLED.getDouble());
                return values;
            }

            double pf = 1.0;
            if (t.getOwner() != null && !this.owns(t)) {
                if (t.getOwner().isEuropean()) {
                    if (portCount < LOW_SETTLEMENT_NUMBER) {
                        values.set(ColonyValueCategory.A_OVERRIDE.ordinal(),
                                   NoValueType.SETTLED.getDouble());
                        return values;
                    }
                    values.set(ColonyValueCategory.A_ADJACENT.ordinal(),
                               values.get(ColonyValueCategory.A_ADJACENT.ordinal())
                               * MOD_OWNED_EUROPEAN * development);
                    continue; // Always ignore production from this tile
                } else {
                    pf = MOD_OWNED_NATIVE;
                    if (portCount > 0) pf *= development;
                }
            }

            // Count production
            for (AbstractGoods ag : t.getSortedPotential()) {
                GoodsType type = ag.getType();
                if (type.isFoodType()) type = foodType;
                int amount = ag.getAmount();
                if (!t.isLand()) amount *= development;
                values.set(ColonyValueCategory.A_PROD.ordinal(),
                           values.get(ColonyValueCategory.A_PROD.ordinal())
                           + amount * type.getProductionWeight() * pf);
                production.incrementCount(type, amount);
                // A few tiles with distinct high production are
                // better than many tiles with low production.
                if (amount > HIGH_PRODUCTION) {
                    highProduction.add(type);
                } else if (amount > GOOD_PRODUCTION) {
                    goodProduction.add(type);
                }
            }

            for (Unit u : t.getUnitList()) {
                if (!owns(u) && u.isOffensiveUnit()
                    && atWarWith(u.getOwner())) {
                    values.set(ColonyValueCategory.A_ADJACENT.ordinal(),
                        values.get(ColonyValueCategory.A_ADJACENT.ordinal())
                        * MOD_ENEMY_UNIT[1]);
                }
            }
        }

        for (GoodsType g : highProduction) {
            values.set(ColonyValueCategory.A_LEVEL.ordinal(),
                       values.get(ColonyValueCategory.A_LEVEL.ordinal())
                       * MOD_HIGH_PRODUCTION);
            goodProduction.remove(g);
        }
        if (!goodProduction.isEmpty()) {
            values.set(ColonyValueCategory.A_LEVEL.ordinal(),
                       values.get(ColonyValueCategory.A_LEVEL.ordinal())
                       * MOD_GOOD_PRODUCTION * goodProduction.size());
        }

        // Apply modifiers for other settlements and units at distance.
        boolean supportingColony = false;
        for (int radius = 2; radius < DISTANCE_MAX; radius++) {
            for (Tile t : getGame().getMap().getCircleTiles(tile, false,
                                                            radius)) {
                Settlement settlement = t.getSettlement();
                if (settlement != null) {
                    if (owns(settlement)) {
                        if (!supportingColony) {
                            supportingColony = true;
                            values.set(ColonyValueCategory.A_NEARBY.ordinal(),
                                values.get(ColonyValueCategory.A_NEARBY.ordinal())
                                * MOD_OWN_COLONY[radius]);
                        }
                    } else if (atWarWith(settlement.getOwner())) {
                        values.set(ColonyValueCategory.A_NEARBY.ordinal(),
                            values.get(ColonyValueCategory.A_NEARBY.ordinal())
                            * MOD_ENEMY_COLONY[radius]);
                    } else {
                        values.set(ColonyValueCategory.A_NEARBY.ordinal(),
                            values.get(ColonyValueCategory.A_NEARBY.ordinal())
                            * MOD_NEUTRAL_COLONY[radius]);
                    }
                }

                for (Unit u : t.getUnitList()) {
                    if (!owns(u) && u.isOffensiveUnit()
                        && atWarWith(u.getOwner())) {
                        values.set(ColonyValueCategory.A_NEARBY.ordinal(),
                            values.get(ColonyValueCategory.A_NEARBY.ordinal())
                            * MOD_ENEMY_UNIT[radius]);
                    }
                }
            }
        }

        // Check availability of key goods
        if (production.getCount(foodType) < FOOD_LOW) {
            values.set(ColonyValueCategory.A_FOOD.ordinal(),
                       values.get(ColonyValueCategory.A_FOOD.ordinal())
                       * MOD_FOOD_LOW);
        }
        int a = ColonyValueCategory.A_GOODS.ordinal() - 1;
        for (GoodsType type : production.keySet()) {
            Integer amount = production.getCount(type);
            double threshold = type.getLowProductionThreshold();
            if (threshold > 0.0) {
                if (++a == values.size()) values.add(1.0);
                if (amount < threshold) {
                    double fraction = (double)amount / threshold;
                    double zeroValue = type.getZeroProductionFactor();
                    values.set(a, (1.0 - fraction) * zeroValue + fraction);
                }
            }
        }

        return values;
    }

    /**
     * Gets the value for building a <code>Colony</code> on
     * the given tile.
     *
     * TODO: tune magic numbers.  Expose more to the spec?
     *
     * @param tile The <code>Tile</code>
     * @return A score for the tile.
     */
    public int getColonyValue(Tile tile) {
        List<Double> values = getAllColonyValues(tile);
        if (values.get(0) < 0.0) return (int)Math.round(values.get(0));
        double v = 1.0;
        for (Double d : values) v *= d;
        return (int)Math.round(v);
    }


    //
    // Miscellaneous
    //

    /**
     * Standardized log of an instance of cheating by this player.
     *
     * @param what A description of the cheating.
     */
    public void logCheat(String what) {
        logger.finest("CHEAT: " + getGame().getTurn().getNumber()
            + " " + Utils.lastPart(getNationId(), ".")
            + " " + what);
    }

    /**
     * Gets the maximum food consumption of any unit types
     * available to this player.
     *
     * @return A maximum food consumption value.
     */
    public int getMaximumFoodConsumption() {
        if (maximumFoodConsumption < 0) {
            Specification spec = getSpecification();
            for (UnitType unitType : spec.getUnitTypeList()) {
                if (unitType.isAvailableTo(this)) {
                    int foodConsumption = 0;
                    for (GoodsType foodType : spec.getFoodGoodsTypeList()) {
                        foodConsumption += unitType.getConsumptionOf(foodType);
                    }
                    if (foodConsumption > maximumFoodConsumption) {
                        maximumFoodConsumption = foodConsumption;
                    }
                }
            }
        }
        return maximumFoodConsumption;
    }

    /**
     * Does this player own something?
     *
     * @param ownable The <code>Ownable</code> to check.
     * @return True if the <code>Ownable</code> is ours.
     */
    public boolean owns(Ownable ownable) {
        return (ownable == null) ? false : this.equals(ownable.getOwner());
    }

    /**
     * Get a <code>FreeColGameObject</code> with the specified
     * identifier and class, owned by this player.
     *
     * Used mainly in message decoding.
     *
     * @param id The object identifier.
     * @param returnClass The expected class of the object.
     * @return The game object, or null if not found.
     * @throws IllegalStateException on failure to validate the object
     *     in any way.
     */
    public <T extends FreeColGameObject> T getOurFreeColGameObject(String id,
        Class<T> returnClass) throws IllegalStateException {
        T t = getGame().getFreeColGameObject(id, returnClass);
        if (t == null) {
            throw new IllegalStateException("Not a " + returnClass.getName()
                + ": " + id);
        } else if (t instanceof Ownable) {
            if (!owns((Ownable)t)) {
                throw new IllegalStateException(returnClass.getName()
                    + " not owned by " + getId() + ": " + id);
            }
        } else {
            throw new IllegalStateException("Not ownable: " + id);
        }
        return t;
    }

    /**
     * Checks if the given <code>Player</code> equals this object.
     *
     * @param o The <code>Player</code> to compare against this object.
     * @return True if the players are the same.
     */
    public boolean equals(Player o) {
        if (o == null) {
            return false;
        } else if (getId() == null || o.getId() == null) {
            // This only happens in the client code with the virtual "enemy
            // privateer" player
            // This special player is not properly associated to the Game and
            // therefore has no identifier.
            // TODO: remove this hack when the virtual "enemy privateer" player
            // is better implemented
            return false;
        } else {
            return getId().equals(o.getId());
        }
    }

    /**
     * Try to fix integrity problems with units that have no owner.
     *
     * @param fix Fix problems if possible.
     * @return Negative if there are problems remaining, zero if
     *     problems were fixed, positive if no problems found at all.
     */
    public int checkIntegrity(boolean fix) {
        int result = 1;
        for (Unit unit : getUnits()) {
            if (unit.getOwner() == null) {
                if (fix) {
                    unit.setOwner(this);
                    logger.warning("Fixed missing owner for: " + unit.getId());
                    result = 0;
                } else {
                    logger.warning("Missing owner for: " + unit.getId());
                    result = -1;
                }
            }
        }
        if (monarch != null) {
            result = Math.min(result, monarch.checkIntegrity(fix));
        }
        return result;
    }


    //
    // Override FreeColObject
    //

    /**
     * {@inheritDoc}
     */
    @Override
    public final FeatureContainer getFeatureContainer() {
        return featureContainer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Ability> getAbilitySet(String id, FreeColGameObjectType fcgot,
                                      Turn turn) {
        Set<Ability> result = super.getAbilitySet(id, fcgot, turn);
        if (id == null || id == Ability.INDEPENDENCE_DECLARED) {
            switch (playerType) {
            case REBEL: case INDEPENDENT:
                result.add(ABILITY_INDEPENDENCE_DECLARED);
                break;
            default: // No other special abilities, just silence the warning.
                break;
            }
        }
        return result;
    }


    // Serialization

    private static final String ADMIN_TAG = "admin";
    private static final String AI_TAG = "ai";
    private static final String ATTACKED_BY_PRIVATEERS_TAG = "attackedByPrivateers";
    private static final String BANKRUPT_TAG = "bankrupt";
    private static final String CURRENT_FATHER_TAG = "currentFather";
    private static final String DEAD_TAG = "dead";
    private static final String ENTRY_LOCATION_TAG = "entryLocation";
    private static final String FOUNDING_FATHERS_TAG = "foundingFathers";
    private static final String GOLD_TAG = "gold";
    private static final String IMMIGRATION_TAG = "immigration";
    private static final String IMMIGRATION_REQUIRED_TAG = "immigrationRequired";
    private static final String LIBERTY_TAG = "liberty";
    private static final String INDEPENDENT_NATION_NAME_TAG = "independentNationName";
    private static final String INTERVENTION_BELLS_TAG = "interventionBells";
    private static final String NATION_ID_TAG = "nationId";
    private static final String NATION_TYPE_TAG = "nationType";
    private static final String NEW_LAND_NAME_TAG = "newLandName";
    private static final String NUMBER_OF_SETTLEMENTS_TAG = "numberOfSettlements";
    private static final String OFFERED_FATHERS_TAG = "offeredFathers";
    private static final String OLD_SOL_TAG = "oldSoL";
    private static final String PLAYER_TAG = "player";
    private static final String PLAYER_TYPE_TAG = "playerType";
    private static final String READY_TAG = "ready";
    private static final String SCORE_TAG = "score";
    private static final String STANCE_TAG = "stance";
    private static final String TAX_TAG = "tax";
    private static final String TENSION_TAG = "tension";
    private static final String USERNAME_TAG = "username";
    // @compat 0.10.7
    private static final String OLD_NATION_ID_TAG = "nationID";
    // end @compat


    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeAttributes(FreeColXMLWriter xw) throws XMLStreamException {
        super.writeAttributes(xw);

        xw.writeAttribute(USERNAME_TAG, name);

        xw.writeAttribute(NATION_ID_TAG, nationId);

        if (nationType != null) {
            xw.writeAttribute(NATION_TYPE_TAG, nationType);
        }

        xw.writeAttribute(ADMIN_TAG, admin);

        xw.writeAttribute(READY_TAG, ready);

        xw.writeAttribute(DEAD_TAG, dead);

        xw.writeAttribute(PLAYER_TYPE_TAG, playerType);

        xw.writeAttribute(AI_TAG, ai);

        if (xw.validFor(this)) {

            xw.writeAttribute(BANKRUPT_TAG, bankrupt);

            xw.writeAttribute(TAX_TAG, tax);

            xw.writeAttribute(GOLD_TAG, gold);

            xw.writeAttribute(IMMIGRATION_TAG, immigration);

            xw.writeAttribute(LIBERTY_TAG, liberty);

            xw.writeAttribute(INTERVENTION_BELLS_TAG, interventionBells);

            if (currentFather != null) {
                xw.writeAttribute(CURRENT_FATHER_TAG, currentFather);
            }

            xw.writeAttribute(IMMIGRATION_REQUIRED_TAG, immigrationRequired);

            xw.writeAttribute(ATTACKED_BY_PRIVATEERS_TAG, attackedByPrivateers);

            xw.writeAttribute(OLD_SOL_TAG, oldSoL);

            xw.writeAttribute(SCORE_TAG, score);
        }

        if (newLandName != null) {
            xw.writeAttribute(NEW_LAND_NAME_TAG, newLandName);
        }

        if (independentNationName != null) {
            xw.writeAttribute(INDEPENDENT_NATION_NAME_TAG, independentNationName);
        }

        if (entryLocation != null) {
            xw.writeLocationAttribute(ENTRY_LOCATION_TAG, entryLocation);
        }

        for (RegionType regionType : RegionType.values()) {
            String key = regionType.getNameIndexKey();
            int index = getNameIndex(key);
            if (index > 0) xw.writeAttribute(key, index);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeChildren(FreeColXMLWriter xw) throws XMLStreamException {
        super.writeChildren(xw);

        if (market != null) market.toXML(xw);

        if (xw.validFor(this)) {

            for (Ability ability : getSortedAbilities()) {
                ability.toXML(xw);
            }

            for (Modifier modifier : getSortedModifiers()) {
                modifier.toXML(xw);
            }

            for (Player p : getSortedCopy(tension.keySet())) {
                xw.writeStartElement(TENSION_TAG);

                xw.writeAttribute(PLAYER_TAG, p);

                xw.writeAttribute(VALUE_TAG, tension.get(p).getValue());

                xw.writeEndElement();
            }

            List<String> playerIds = new ArrayList<String>(stance.keySet());
            Collections.sort(playerIds);
            for (String pid : playerIds) {
                Stance s = stance.get(pid);
                if (s == Stance.UNCONTACTED) continue;

                xw.writeStartElement(STANCE_TAG);

                xw.writeAttribute(PLAYER_TAG, pid);

                xw.writeAttribute(VALUE_TAG, stance.get(pid));

                xw.writeEndElement();
            }

            for (HistoryEvent event : history) { // Already in order
                event.toXML(xw);
            }

            for (TradeRoute route : getSortedCopy(tradeRoutes)) {
                route.toXML(xw);
            }

            if (highSeas != null) highSeas.toXML(xw);
            
            xw.writeToListElement(FOUNDING_FATHERS_TAG, foundingFathers);

            xw.writeToListElement(OFFERED_FATHERS_TAG, offeredFathers);

            if (europe != null) europe.toXML(xw);

            if (monarch != null) monarch.toXML(xw);

            for (ModelMessage m : getSortedCopy(modelMessages)) m.toXML(xw);

            if (lastSales != null) {
                for (LastSale sale : getSortedCopy(lastSales.values())) {
                    sale.toXML(xw);
                }
            }

            Turn turn = getGame().getTurn();
            for (Modifier modifier : getSortedModifiers()) {
                if (modifier.isTemporary() && !modifier.isOutOfDate(turn)) {
                    modifier.toXML(xw);
                }
            }

        } else {
            Player player = xw.getClientPlayer();
            Tension t = getTension(player);
            if (t != null) {
                xw.writeStartElement(TENSION_TAG);

                xw.writeAttribute(PLAYER_TAG, player);

                xw.writeAttribute(VALUE_TAG, t.getValue());

                xw.writeEndElement();
            }

            Stance s = getStance(player);
            if (s != null && s != Stance.UNCONTACTED) {
                xw.writeStartElement(STANCE_TAG);
                
                xw.writeAttribute(PLAYER_TAG, player);

                xw.writeAttribute(VALUE_TAG, s);

                xw.writeEndElement();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readAttributes(FreeColXMLReader xr) throws XMLStreamException {
        super.readAttributes(xr);

        final Specification spec = getSpecification();
        final Game game = getGame();

        name = xr.getAttribute(USERNAME_TAG, (String)null);

        nationId = xr.getAttribute(NATION_ID_TAG,
            // @compat 0.10.7
            xr.getAttribute(OLD_NATION_ID_TAG,
            // end @compat 0.10.7
                (String)null));

        if (isUnknownEnemy()) {
            nationType = null;
        } else {
            changeNationType(xr.getType(spec, NATION_TYPE_TAG,
                                        NationType.class, (NationType)null));;
        }

        admin = xr.getAttribute(ADMIN_TAG, false);

        gold = xr.getAttribute(GOLD_TAG, 0);

        immigration = xr.getAttribute(IMMIGRATION_TAG, 0);

        liberty = xr.getAttribute(LIBERTY_TAG, 0);

        interventionBells = xr.getAttribute(INTERVENTION_BELLS_TAG, 0);

        oldSoL = xr.getAttribute(OLD_SOL_TAG, 0);

        score = xr.getAttribute(SCORE_TAG, 0);

        ready = xr.getAttribute(READY_TAG, false);

        ai = xr.getAttribute(AI_TAG, false);

        dead = xr.getAttribute(DEAD_TAG, false);

        bankrupt = xr.getAttribute(BANKRUPT_TAG, false);

        tax = xr.getAttribute(TAX_TAG, 0);

        playerType = xr.getAttribute(PLAYER_TYPE_TAG,
                                     PlayerType.class, (PlayerType)null);

        currentFather = xr.getType(spec, CURRENT_FATHER_TAG,
                                   FoundingFather.class, (FoundingFather)null);

        immigrationRequired = xr.getAttribute(IMMIGRATION_REQUIRED_TAG, 12);

        newLandName = xr.getAttribute(NEW_LAND_NAME_TAG, (String)null);

        independentNationName = xr.getAttribute(INDEPENDENT_NATION_NAME_TAG,
                                                (String)null);

        attackedByPrivateers = xr.getAttribute(ATTACKED_BY_PRIVATEERS_TAG,
                                               false);

        entryLocation = xr.getLocationAttribute(game, ENTRY_LOCATION_TAG,
                                                true);

        for (RegionType regionType : RegionType.values()) {
            String key = regionType.getNameIndexKey();
            int index = xr.getAttribute(key, -1);
            if (index > 0) setNameIndex(key, index);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readChildren(FreeColXMLReader xr) throws XMLStreamException {
        // Clear containers.
        tension.clear();
        stance.clear();
        foundingFathers.clear();
        offeredFathers.clear();
        europe = null;
        monarch = null;
        history.clear();
        tradeRoutes.clear();
        modelMessages.clear();
        lastSales = null;
        highSeas = null;
        featureContainer.clear();
        if (nationType != null) addFeatures(nationType);

        super.readChildren(xr);

        recalculateBellsBonus(); // Bells bonuses depend on tax
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readChild(FreeColXMLReader xr) throws XMLStreamException {
        final Specification spec = getSpecification();
        final Game game = getGame();
        final String tag = xr.getLocalName();

        if (FOUNDING_FATHERS_TAG.equals(tag)) {
            List<FoundingFather> ffs = xr.readList(spec, FOUNDING_FATHERS_TAG,
                                                   FoundingFather.class);
            if (ffs != null) {
                for (FoundingFather ff : ffs) {
                    addFather(ff); // addFather adds the features
                }
            }
        
        } else if (OFFERED_FATHERS_TAG.equals(tag)) {
            List<FoundingFather> ofs = xr.readList(spec, OFFERED_FATHERS_TAG,
                                                   FoundingFather.class);
            offeredFathers.addAll(ofs);

        } else if (STANCE_TAG.equals(tag)) {
            Player player = xr.makeFreeColGameObject(game, PLAYER_TAG,
                                                     Player.class, true);
            stance.put(player.getId(),
                xr.getAttribute(VALUE_TAG, Stance.class, Stance.UNCONTACTED));
            xr.closeTag(STANCE_TAG);

        } else if (TENSION_TAG.equals(tag)) {
            tension.put(xr.makeFreeColGameObject(game, PLAYER_TAG,
                                                 Player.class, true),
                        new Tension(xr.getAttribute(VALUE_TAG, 0)));
            xr.closeTag(TENSION_TAG);
        
        } else if (Ability.getXMLElementTagName().equals(tag)) {
            addAbility(new Ability(xr, spec));

        } else if (Europe.getXMLElementTagName().equals(tag)) {
            europe = xr.readFreeColGameObject(game, Europe.class);

        } else if (HighSeas.getXMLElementTagName().equals(tag)) {
            highSeas = xr.readFreeColGameObject(game, HighSeas.class);

        } else if (HistoryEvent.getXMLElementTagName().equals(tag)) {
            getHistory().add(new HistoryEvent(xr));

        } else if (LastSale.getXMLElementTagName().equals(tag)) {
            addLastSale(new LastSale(xr));

        } else if (Market.getXMLElementTagName().equals(tag)) {
            market = xr.readFreeColGameObject(game, Market.class);

        } else if (ModelMessage.getXMLElementTagName().equals(tag)) {
            addModelMessage(new ModelMessage(xr));

        } else if (Modifier.getXMLElementTagName().equals(tag)) {
            addModifier(new Modifier(xr, spec));

        } else if (Monarch.getXMLElementTagName().equals(tag)) {
            monarch = xr.readFreeColGameObject(game, Monarch.class);

        } else if (TradeRoute.getXMLElementTagName().equals(tag)) {
            tradeRoutes.add(xr.readFreeColGameObject(game, TradeRoute.class));

        } else {
            super.readChild(xr);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(32);
        sb.append(getName()).append(" (").append(nationId).append(")");
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    public String getXMLTagName() { return getXMLElementTagName(); }

    /**
     * Gets the tag name of the root element representing this object.
     *
     * @return "player"
     */
    public static String getXMLElementTagName() {
        return "player";
    }
}
