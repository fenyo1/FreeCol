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

package net.sf.freecol.client.gui.dialog;

import java.awt.Component;
import java.util.logging.Logger;

import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import net.miginfocom.swing.MigLayout;

import net.sf.freecol.client.FreeColClient;
import net.sf.freecol.client.gui.GUI;
import net.sf.freecol.client.gui.label.GoodsLabel;
import net.sf.freecol.client.gui.panel.*;
import net.sf.freecol.common.i18n.Messages;
import net.sf.freecol.common.option.GameOptions;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.BreedingData;
import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.Goods;
import net.sf.freecol.common.model.GoodsType;


/**
 * A dialog to display reproduction settings of a colony.
 */
public final class ReproductionDialog extends FreeColConfirmDialog {

    private static final Logger logger = Logger.getLogger(ReproductionDialog.class.getName());

    private final Colony colony;

    private JCheckBox newColonists;
    private JPanel breedingPanel;


    /**
     * Creates a dialog to display the reproduction settings.
     *
     * @param freeColClient The {@code FreeColClient} for the game.
     * @param frame The owner frame.
     * @param colony The {@code Colony}.
     */
    public ReproductionDialog(FreeColClient freeColClient, JFrame frame, Colony colony) {
        super(freeColClient, frame);

        this.colony = colony;

        breedingPanel = new MigPanel(new MigLayout("wrap 2"));
        breedingPanel.setOpaque(false);

        // newColonists checkbox
        newColonists = new JCheckBox(Messages.message("reproductionDialog.newColonists"),
            (colony.getMakesNewColonists() == null ? colony.getSpecification().getBoolean(GameOptions.DEFAULT_NEW_COLONISTS) : colony.getMakesNewColonists()));
        Utility.localizeToolTip(newColonists, "reproductionDialog.newColonists.shortDescription");
        breedingPanel.add(newColonists);
        breedingPanel.add(new MigPanel("EmptyPanel"));

        for (GoodsType type : freeColClient.getGame().getSpecification().getGoodsTypeList()) {
            if (type.isBreedable()) {
                breedingPanel.add(new BreedingGoodsPanel(freeColClient, colony, type));
            }
        }

        JScrollPane scrollPane = new JScrollPane(breedingPanel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setBorder(null);

        MigPanel panel = new MigPanel(new MigLayout("fill, wrap 1", "", ""));
        panel.add(Utility.localizedHeader(Messages.nameKey("reproductionDialog"), false), "align center");
        panel.add(scrollPane, "grow");
        panel.setSize(panel.getPreferredSize());

        ImageIcon icon = new ImageIcon(getImageLibrary().getSmallSettlementImage(colony));
        initializeConfirmDialog(frame, true, panel, icon, "ok", "cancel");
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean getResponse() {
        Boolean result = super.getResponse();
        if (result) {
            colony.setMakesNewColonists(newColonists.isSelected());
            freeColClient.getInGameController().setMakesNewColonists(colony);
            for (Component c : breedingPanel.getComponents()) {
                if (c instanceof BreedingGoodsPanel) {
                    ((BreedingGoodsPanel)c).saveSettings();
                }
            }
        }
        breedingPanel = null;
        return result;
    }


    private class BreedingGoodsPanel extends MigPanel {

        private final Colony colony;

        private final GoodsType goodsType;

        private final JCheckBox consumeOnlySurplus;

        private final JLabel consumeRatioLabel;

        private final JSpinner consumeRatio;


        public BreedingGoodsPanel(FreeColClient freeColClient, Colony colony, GoodsType goodsType) {
            super("BreedingGoodsPanelUI");

            this.colony = colony;
            this.goodsType = goodsType;

            setLayout(new MigLayout("wrap 2", "", ""));
            setOpaque(false);
            setBorder(Utility.localizedBorder(goodsType));
            Utility.padBorder(this, 6,6,6,6);

            BreedingData breedingData = colony.getBreedingData(goodsType);

            // goods label
            Goods goods = new Goods(colony.getGame(), colony, goodsType, colony.getGoodsCount(goodsType));
            GoodsLabel goodsLabel = new GoodsLabel(freeColClient.getGUI(), goods);
            goodsLabel.setHorizontalAlignment(JLabel.LEADING);
            add(goodsLabel, "span 1 2");

            // consumeOnlySurplus checkbox
            consumeOnlySurplus = new JCheckBox(Messages.message("reproductionDialog.consumeOnlySurplus"), breedingData.getConsumeOnlySurplus());
            Utility.localizeToolTip(consumeOnlySurplus, "reproductionDialog.consumeOnlySurplus.shortDescription");
            add(consumeOnlySurplus);

            // consumeRatio settings
            MigPanel panel = new MigPanel(new MigLayout("fill, wrap 2", "", ""));
            consumeRatioLabel = new JLabel(Messages.message("reproductionDialog.consumeRatio"));
            Utility.localizeToolTip(consumeRatioLabel, "reproductionDialog.consumeRatio.shortDescription");
            panel.add(consumeRatioLabel);
            SpinnerNumberModel consumeRatioModel = new SpinnerNumberModel(breedingData.getConsumeRatio(), 0, 100, 1);
            consumeRatio = new JSpinner(consumeRatioModel);
            Utility.localizeToolTip(consumeRatio, "reproductionDialog.consumeRatio.shortDescription");
            panel.add(consumeRatio);
            add(panel);

            setSize(getPreferredSize());
        }

        public void saveSettings() {
            BreedingData breedingData = colony.getBreedingData(goodsType);

            breedingData.setConsumeOnlySurplus(consumeOnlySurplus.isSelected());
            breedingData.setConsumeRatio(((SpinnerNumberModel)consumeRatio.getModel()).getNumber().intValue());
            freeColClient.getInGameController().setGoodsBreeding(colony, goodsType);
        }
    }
}
