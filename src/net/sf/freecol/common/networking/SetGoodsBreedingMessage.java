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

package net.sf.freecol.common.networking;

import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.BreedingData;
import net.sf.freecol.common.model.Game;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.server.FreeColServer;
import net.sf.freecol.server.model.ServerPlayer;

import org.w3c.dom.Element;


/**
 * The message sent when setting goods breeding data.
 */
public class SetGoodsBreedingMessage extends DOMMessage {

    public static final String TAG = "setGoodsBreeding";
    private static final String COLONY_TAG = "colony";

    /** The identifier of the colony where the goods breeding data are set. */
    private final String colonyId;

    /** The new BreedingData. */
    private final BreedingData data;


    /**
     * Create a new {@code SetGoodsBreedingMessage} with the
     * supplied colony and data.
     *
     * @param colony The {@code Colony} where the breeding data are set.
     * @param data The new {@code BreedingData}.
     */
    public SetGoodsBreedingMessage(Colony colony, BreedingData data) {
        super(TAG);

        this.colonyId = colony.getId();
        this.data = data;
    }

    /**
     * Create a new {@code SetGoodsBreedingMessage} from a
     * supplied element.
     *
     * @param game The {@code Game} this message belongs to.
     * @param element The {@code Element} to use to create the message.
     */
    public SetGoodsBreedingMessage(Game game, Element element) {
        super(TAG);

        this.colonyId = getStringAttribute(element, COLONY_TAG);
        this.data = getChild(game, element, 0, BreedingData.class);
    }


    /**
     * {@inheritDoc}
     */
    public static MessagePriority getMessagePriority() {
        return MessagePriority.NORMAL;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public ChangeSet serverHandler(FreeColServer freeColServer,
                                   ServerPlayer serverPlayer) {
        Colony colony;
        try {
            colony = serverPlayer.getOurFreeColGameObject(this.colonyId,
                                                          Colony.class);
        } catch (Exception e) {
            return serverPlayer.clientError(e.getMessage());
        }

        // Proceed to set.
        return freeColServer.getInGameController()
            .setGoodsBreeding(serverPlayer, colony, this.data);
    }

    /**
     * Convert this SetGoodsBreedingMessage to XML.
     *
     * @return The XML representation of this message.
     */
    @Override
    public Element toXMLElement() {
        return new DOMMessage(TAG,
            COLONY_TAG, this.colonyId)
            .add(data).toXMLElement();
    }
}
