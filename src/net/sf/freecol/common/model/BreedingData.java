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

package net.sf.freecol.common.model;

import javax.xml.stream.XMLStreamException;

import net.sf.freecol.common.io.FreeColXMLReader;
import net.sf.freecol.common.io.FreeColXMLWriter;


/**
 * Objects of this class hold the breeding data for a particular type of goods.
 */
public class BreedingData extends FreeColObject {

    public static final String TAG = "breedingData";

    /** Whether the breeding of the goods type can consume only the surplus. */
    private boolean consumeOnlySurplus;

    /** The breeding of the goods type can consume only this percent of the surplus. */
    private int consumeRatio;


    /**
     * Trivial constructor for Game.newInstance.
     */
    public BreedingData() {}

    /**
     * Creates a new {@code BreedingData} instance with default settings.
     *
     * @param goodsType The {@code GoodsType} this data refers to.
     * @param consumeOnlySurplus The initial value of consumeOnlySurplus.
     * @param consumeRatio The initial value of consumeRatio.
     */
    public BreedingData(GoodsType goodsType, boolean consumeOnlySurplus, int consumeRatio) {
        setId(goodsType.getId());
        this.consumeOnlySurplus = consumeOnlySurplus;
        this.consumeRatio = consumeRatio;
    }

    /**
     * Create a new {@code BreedingData} by reading a stream.
     *
     * @param xr The {@code FreeColXMLReader} to read.
     * @exception XMLStreamException if there is a problem reading the stream.
     */
    public BreedingData(FreeColXMLReader xr) throws XMLStreamException {
        readFromXML(xr);
    }


    /**
     * Get the consumeOnlySurplus for this data.
     *
     * @return The consumeOnlySurplus.
     */
    public final boolean getConsumeOnlySurplus() {
        return consumeOnlySurplus;
    }

    /**
     * Set the consumeOnlySurplus for this data.
     *
     * @param newConsumeOnlySurplus The new consumeOnlySurplus value.
     * @return This breeding data.
     */
    public final BreedingData setConsumeOnlySurplus(final boolean newConsumeOnlySurplus) {
        this.consumeOnlySurplus = newConsumeOnlySurplus;
        return this;
    }

    /**
     * Get the consumeRatio for this data.
     *
     * @return The consumeRatio.
     */
    public final int getConsumeRatio() {
        return consumeRatio;
    }

    /**
     * Set the consumeRatio for this data.
     *
     * @param newConsumeRatio The new consumeRatio value.
     * @return This breeding data.
     */
    public final BreedingData setConsumeRatio(final int newConsumeRatio) {
        this.consumeRatio = newConsumeRatio;
        return this;
    }


    // Serialization

    private static final String CONSUME_ONLY_SURPLUS_TAG = "consumeOnlySurplus";
    private static final String CONSUME_RATIO_TAG = "consumeRatio";


    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeAttributes(FreeColXMLWriter xw) throws XMLStreamException {
        super.writeAttributes(xw);

        xw.writeAttribute(CONSUME_ONLY_SURPLUS_TAG, consumeOnlySurplus);

        xw.writeAttribute(CONSUME_RATIO_TAG, consumeRatio);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readAttributes(FreeColXMLReader xr) throws XMLStreamException {
        super.readAttributes(xr);

        consumeOnlySurplus = xr.getAttribute(CONSUME_ONLY_SURPLUS_TAG, true);

        consumeRatio = xr.getAttribute(CONSUME_RATIO_TAG, 50);
    }

    /**
     * {@inheritDoc}
     */
    public String getXMLTagName() { return TAG; }
}
