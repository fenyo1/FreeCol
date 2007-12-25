/**
 *  Copyright (C) 2002-2007  The FreeCol Team
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
import javax.xml.stream.XMLStreamWriter;

import net.sf.freecol.client.gui.i18n.Messages;

public abstract class FreeColGameObjectType extends FreeColObject {

    private String id;
    private int index;

    protected final void setId(final String id) {
        this.id = id;
    }

    protected final void setIndex(final int index) {
        this.index = index;
    }

    public final String getId() {
        return id;
    }

    public final int getIndex() {
        return index;
    }

    public final String getName() {
        return Messages.message(id + ".name");
    }

    public final String getDescription() {
        return Messages.message(id + ".description");
    }

    public boolean hasAbility(String id) {
        return false;
    }

    public Modifier getModifier(String id) {
        return null;
    }

    protected void toXMLImpl(XMLStreamWriter out) throws XMLStreamException {};
    
    /**
     * Use only for debugging purposes! A human-readable and localized name is
     * returned by getName().
     */
    public String toString() {
        return id;
    }
}
