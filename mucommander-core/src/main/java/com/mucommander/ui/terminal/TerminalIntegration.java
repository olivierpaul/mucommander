/*
 * This file is part of muCommander, http://www.mucommander.com
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mucommander.ui.terminal;

import com.jediterm.terminal.ui.JediTermWidget;
import com.mucommander.commons.conf.ConfigurationEvent;
import com.mucommander.commons.conf.ConfigurationListener;
import com.mucommander.conf.MuConfigurations;
import com.mucommander.conf.MuPreference;
import com.mucommander.conf.MuPreferences;
import com.mucommander.desktop.ActionType;
import com.mucommander.text.Translator;
import com.mucommander.ui.action.ActionId;
import com.mucommander.ui.action.ActionKeymap;
import com.mucommander.ui.main.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This class integrates Terminal (via TerminalWidget) into bottom pane of provided vertical split pane.
 */
public class TerminalIntegration {

    protected final Logger LOGGER = LoggerFactory.getLogger(TerminalIntegration.class);

    private final MainFrame mainFrame;

    private final JSplitPane verticalSplitPane;

    private JediTermWidget terminal;

    /**
     * When divider is close to max by this value we conclude it is maximised.
     */
    private static final float TREAT_AS_MAXIMIZED = 0.3f;
    private static final String CHANGE_DIR_FORMAT = " cd \"%s\"" + System.lineSeparator(); // leading space added deliberately to skip history (sometimes doesn't work, tho :/)

    private String cwd; // keep it as String or as MonitoredFile maybe?
    private boolean terminalMaximized; // is terminal maximized?
    private int lastMinDividerLocation; // last vertical split pane divider location when minimized
    private int lastMaxDividerLocation; // last vertical split pane divider location when maximised

    @SuppressWarnings("FieldCanBeLocal")
    private final ConfigurationListener shellConfigurationListener;

    /**
     * Integrates Terminal in provided verticalSplitPane belonging to a given mainFrame.
     * @param mainFrame mainFrame instance
     * @param verticalSplitPane the JSplitPane instance of mainFrame
     */
    public TerminalIntegration(MainFrame mainFrame, JSplitPane verticalSplitPane) {
        this.mainFrame = mainFrame;
        this.verticalSplitPane = verticalSplitPane;
        prepareVerticalSplitPaneForTerminal();
        shellConfigurationListener = new ConfigurationListener() {
            @Override
            public void configurationChanged(ConfigurationEvent event) {
                switch (event.getVariable()) {
                case MuPreferences.CUSTOM_SHELL:
                    if (!MuConfigurations.getPreferences().getVariable(
                            MuPreference.USE_CUSTOM_SHELL, MuPreferences.DEFAULT_USE_CUSTOM_SHELL)) {
                        return;
                    }
                case MuPreferences.USE_CUSTOM_SHELL:
                    if (terminal != null) {
                        terminal.close();
                        // if the terminal is not visible, it will be set with the selected shell when we show it.
                        // otherwise, we need to refresh it. however, it seems we can't discover whether the terminal
                        // had the focus before updating the configuration so in this case we show the terminal anyway.
                        if (terminal.isVisible()) {
                            SwingUtilities.invokeLater(TerminalIntegration.this::showTerminal);
                        }
                    }
                }
            }
        };
        MuConfigurations.addPreferencesListener(shellConfigurationListener);
    }

    /**
     * Toggles the Terminal, i.e. shows (maximized) or hides it (minimized).
     */
    public void toggleTerminal() {
        if (!isTerminalMaximized() || terminal == null) {
            showTerminal();
        } else {
            hideTerminal();
        }
    }

    private void setTerminalMaximized(boolean terminalMaximized) {
        this.terminalMaximized = terminalMaximized;
    }

    private boolean isTerminalMaximized() {
        return terminalMaximized;
    }

    private KeyAdapter termCloseKeyHandler() {
        return new KeyAdapter() {
            public void keyPressed(KeyEvent keyEvent) {
                KeyStroke pressedKeyStroke = KeyStroke.getKeyStrokeForEvent(keyEvent);
                KeyStroke accelerator = ActionKeymap.getAccelerator(
                        ActionId.asTerminalAction(ActionType.ToggleTerminal.getId()));
                KeyStroke alternateAccelerator = ActionKeymap.getAlternateAccelerator(
                        ActionId.asTerminalAction(ActionType.ToggleTerminal.getId()));
                if (pressedKeyStroke.equals(accelerator) || pressedKeyStroke.equals(alternateAccelerator)) {
                    keyEvent.consume();
                    hideTerminal();
                } else if (!terminal.getTtyConnector().isConnected()) {
                    // just close terminal if it is not active/connected (for example when sb typed 'exit')
                    hideTerminal();
                }
            }
        };
    }

    private JediTermWidget getTerminal(String initialPath) {
        var terminal = TerminalWidget.createTerminal(initialPath);
        cwd = initialPath;
        return terminal;
    }

    private void showTerminal() {
        try {
            LOGGER.info("Going to show Terminal...");
            var newCwd = mainFrame.getActivePanel().getCurrentFolder().getAbsolutePath();
            // If !connected means that terminal process has ended (via `exit` command for ex.).
            if (terminal == null || !terminal.getTtyConnector().isConnected()) {
                Cursor orgCursor = mainFrame.getJFrame().getCursor();
                try {
                    mainFrame.getJFrame().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                    terminal = getTerminal(newCwd);
                    terminal.getTerminalPanel().addCustomKeyListener(termCloseKeyHandler());
                    // TODO do this better? For now 2 lines ~height + 20%
                    terminal.setMinimumSize(new Dimension(-1,
                            (int) (terminal.getFontMetrics(terminal.getFont()).getHeight() * 2 * 1.2)));
                    verticalSplitPane.setBottomComponent(terminal);
                    // don't know exactly why, but it should be after addCustomKeyListener (https://github.com/mucommander/mucommander/issues/1187)
                    terminal.getTerminalPanel().addFocusListener(new FocusAdapter() {
                        public void focusGained(FocusEvent e) {
                            syncCWD(mainFrame.getActivePanel().getCurrentFolder().getAbsolutePath());
                        }
                    });
                } finally {
                    mainFrame.getJFrame().setCursor(orgCursor);
                }
            } else {
                syncCWD(newCwd);
            }

            verticalSplitPane.setDividerLocation(lastMaxDividerLocation);

            SwingUtilities.invokeLater(terminal::requestFocusInWindow);
            setTerminalMaximized(true);
        } catch (Exception e) {
            LOGGER.error("Caught exception while trying to show Terminal", e);
            hideTerminal();
        }
    }

    private void syncCWD(String newCwd) {
        if (cwd == null || !cwd.equals(newCwd)) {
            // TODO check somehow if term is busy..... or find another way to set CWD
            // TODO cont'd: In Idea they've got TerminalUtil#hasRunningCommands for that...
            try {
                var cmd = String.format(CHANGE_DIR_FORMAT, newCwd);
                terminal.getTtyConnector().write(cmd);
                cwd = newCwd;
            } catch (IOException e) {
                LOGGER.error("Cannot sync table's CWD with terminal", e);
            }
        }
    }

    private void hideTerminal() {
        LOGGER.info("Going to hide Terminal...");
        if (terminal != null && !terminal.getTtyConnector().isConnected()) {
            verticalSplitPane.remove(terminal);
            terminal = null;
        }

        // try to use last location falling back to the minimum of terminal constraints
        // however, user may move divider below terminal constraints (hide it completely) and we
        // should accept that
        verticalSplitPane.setDividerLocation(
                lastMinDividerLocation <= 0 ||    // ignore unknown or max
                        lastMinDividerLocation > verticalSplitPane.getHeight() || // or outside vertical height
                        lastMinDividerLocation < verticalSplitPane.getHeight() * TREAT_AS_MAXIMIZED // or too close to max
                        ? verticalSplitPane.getMaximumDividerLocation() : lastMinDividerLocation);

        SwingUtilities.invokeLater(mainFrame.getActiveTable()::requestFocusInWindow);
        setTerminalMaximized(false);
    }

    private void alterSplitPaneButton(String buttonName, JSplitPane splitPane, Runnable action, String tooltip) {
        // https://stackoverflow.com/a/31709568/1715521
        try {
            Field field = BasicSplitPaneDivider.class.getDeclaredField(buttonName);
            field.setAccessible(true);
            var maxTries = 10;
            for (int i = 0; i < maxTries; i++) {
                JButton oneTouchButton = (JButton) field.get(((BasicSplitPaneUI) splitPane.getUI()).getDivider());
                if (oneTouchButton != null) {
                    oneTouchButton.setToolTipText(tooltip);
                    oneTouchButton.setActionCommand(buttonName);
                    oneTouchButton.addActionListener(e -> SwingUtilities.invokeLater(action));
                    break;
                } else {
                    LOGGER.debug("Vertical split pane is not ready, unable to alter its buttons (attempt {} of {})", i + 1, maxTries);
                    Thread.sleep(10); // :/ but it should not delay start-up much
                }
            }
        } catch (Exception e) {
            LOGGER.error("Problem running reflection on vertical split pane: {}", e.getMessage(), e);
        }
    }

    private void alterSplitPaneDivider(JSplitPane splitPane, Runnable action, String tooltip) {

        try {
            // https://stackoverflow.com/a/27432464/1715521
            BasicSplitPaneUI splitUI = ((BasicSplitPaneUI) splitPane.getUI());
            splitPane.setToolTipText(tooltip);

            splitUI.getDivider().addMouseListener(new MouseAdapter() {

                Timer tooltipTimer;

                public void mouseClicked(MouseEvent e) {
                    SwingUtilities.invokeLater(action);
                }

                // force tooltip when mouse enters divider
                public void mouseEntered(MouseEvent e) {
                    if (tooltipTimer != null) {
                        return;
                    }
                    tooltipTimer = new java.util.Timer();
                    tooltipTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            showTooltip(splitPane, splitUI, e.getX(), e.getY());
                            cancelTimer();
                        }
                    }, 1000L);
                }

                public void mouseExited(MouseEvent e) {
                    cancelTimer();
                }

                private void cancelTimer() {
                    Timer t = tooltipTimer;
                    if (t != null) {
                        t.cancel();
                        tooltipTimer = null;
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("Problem running reflection on vertical split pane: {}", e.getMessage(), e);
        }
    }

    private void showTooltip(JSplitPane splitPane, BasicSplitPaneUI splitUI, int x, int y) {
        // https://stackoverflow.com/a/39803911/1715521
        final ToolTipManager ttm = ToolTipManager.sharedInstance();
        final int orgInitDelay = ttm.getInitialDelay();
        final int orgDismDelay = ttm.getInitialDelay();
        ttm.setInitialDelay(0);
        ttm.setDismissDelay(4000);
        ttm.mouseMoved(new MouseEvent(splitPane, 0, 0, 0,
                splitUI.getDivider().getX() + x,
                splitUI.getDivider().getY() + y, 0, false));
        SwingUtilities.invokeLater(() -> {
            ttm.setInitialDelay(orgInitDelay);
            ttm.setDismissDelay(orgDismDelay);
        });
    }

    private void prepareVerticalSplitPaneForTerminal() {
        alterSplitPaneButton("leftButton", verticalSplitPane,
                this::showTerminal,
                Translator.get(ActionType.ToggleTerminal + ".show"));
        alterSplitPaneButton("rightButton", verticalSplitPane,
                this::hideTerminal,
                Translator.get(ActionType.ToggleTerminal + ".hide"));
        alterSplitPaneDivider(
                verticalSplitPane,
                this::toggleTerminal,
                Translator.get(ActionType.ToggleTerminal + ".toggle"));

        verticalSplitPane.addPropertyChangeListener(
                JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> {
                    if (terminal != null) {
                        var location = (Integer) e.getNewValue();
                        if (terminal == null) {
                            setTerminalMaximized(false);
                        } else if (location > verticalSplitPane.getMaximumDividerLocation() * TREAT_AS_MAXIMIZED) {
                            lastMinDividerLocation = location;
                            setTerminalMaximized(false);
                        } else {
                            lastMaxDividerLocation = location;
                            setTerminalMaximized(true);
                        }
                    }
                });
    }
}
