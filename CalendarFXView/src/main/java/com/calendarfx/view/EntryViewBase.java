/*
 *  Copyright (C) 2017 Dirk Lemmermann Software & Consulting (dlsc.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.calendarfx.view;

import com.calendarfx.model.Calendar;
import com.calendarfx.model.Entry;
import com.calendarfx.view.DateControl.EntryContextMenuParameter;
import com.calendarfx.view.DateControl.EntryDetailsParameter;
import javafx.animation.ScaleTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import javafx.collections.WeakListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.scene.CacheHint;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.util.Callback;
import javafx.util.Duration;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.control.PropertySheet.Item;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static javafx.scene.control.SelectionMode.MULTIPLE;
import static javafx.scene.input.MouseButton.PRIMARY;

/**
 * The base class for all views that are representing calendar entries. There
 * are specializations of this class for the {@link DayView}, the
 * {@link DetailedWeekView}, and the {@link MonthView}. Each date control class uses
 * their own entry factory to create entry view instances.
 * <p>
 * This view uses four pseudo classes:
 * <ul>
 * <li>dragged - when the user drags the view</li>
 * <li>dragged-start - when the user changes the start time of the view</li>
 * <li>dragged-end - when the user changes the end time of the view</li>
 * </ul>
 * These states can be used to visualize the view differently during editing
 * operations. The default styling causes the original view of a dragged entry
 * to lower its opacity so that the user can still see the view at its original
 * location as a "ghost". If the user only changes the start or end time then
 * the opacity of the original entry view will be set to 0, which means the view
 * will be invisible.
 *
 * @param <T> the type of date control where the entry is being used
 * @see DayView#entryViewFactoryProperty()
 * @see MonthView#entryViewFactoryProperty()
 */
public abstract class EntryViewBase<T extends DateControl> extends CalendarFXControl implements Comparable<EntryViewBase<T>> {
    public final static String DRAGGED = "dragged";
    public final static String DRAGGEDEND = "dragged-end";
    public final static String SELECTED = "selected";
    public final static String HIDDEN = "hidden";
    private static final PseudoClass DRAGGED_PSEUDO_CLASS = PseudoClass.getPseudoClass("dragged");

    private static final PseudoClass DRAGGED_START_PSEUDO_CLASS = PseudoClass.getPseudoClass("dragged-start");

    private static final PseudoClass DRAGGED_END_PSEUDO_CLASS = PseudoClass.getPseudoClass("dragged-end");

    private static final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");

    private Entry<?> entry;

    private final ListChangeListener<? super String> styleListener = change -> {
        while (change.next()) {
            if (change.wasAdded()) {
                getStyleClass().addAll(change.getAddedSubList());
            } else if (change.wasRemoved()) {
                getStyleClass().removeAll(change.getRemoved());
            }
        }
    };

    private final WeakListChangeListener<? super String> weakStyleListener = new WeakListChangeListener<>(styleListener);

    /**
     * Constructs a new view for the given entry.
     *
     * @param entry the calendar entry
     */
    protected EntryViewBase(Entry<?> entry) {
        this.entry = requireNonNull(entry);

        entry.getStyleClass().addListener(weakStyleListener);
        getStyleClass().addAll(entry.getStyleClass());

        setFocusTraversable(true);

        focusedProperty().addListener(it -> processFocus());

        addEventHandler(MouseEvent.MOUSE_CLICKED, evt -> showDetails(evt, evt.getScreenX(), evt.getScreenY()));
        addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, evt -> {
            evt.consume();
            DateControl dateControl = getDateControl();
            if (dateControl != null) {
                /*
                 * Normally date control should always exist when we are in this
                 * situation, but in the samples there is no control.
                 */
                Callback<EntryContextMenuParameter, ContextMenu> callback = dateControl.getEntryContextMenuCallback();
                if (callback != null) {
                    EntryContextMenuParameter param = new EntryContextMenuParameter(evt, dateControl, EntryViewBase.this);
                    ContextMenu menu = callback.call(param);
                    if (menu != null) {
                        setContextMenu(menu);
                        menu.show(EntryViewBase.this, evt.getScreenX(), evt.getScreenY());
                    }
                }
            }
        });

        @SuppressWarnings("unchecked")
        MapChangeListener<? super Object, ? super Object> propertiesListener = change -> {
            if (change.wasAdded()) {
                if (change.getKey().equals("startDate")) {
                    setStartDate((LocalDate) change.getValueAdded());
                } else if (change.getKey().equals("endDate")) {
                    setEndDate((LocalDate) change.getValueAdded());
                } else if (change.getKey().equals("startTime")) {
                    setStartTime((LocalTime) change.getValueAdded());
                } else if (change.getKey().equals("endTime")) {
                    setEndTime((LocalTime) change.getValueAdded());
                } else if (change.getKey().equals("position")) {
                    setPosition((Position) change.getValueAdded());
                } else if (change.getKey().equals(DRAGGED)) {
                    Boolean onOff = (Boolean) change.getValueAdded();
                    dragged.set(onOff);
                    getProperties().remove(DRAGGED);
                } else if (change.getKey().equals("dragged-start")) {
                    Boolean onOff = (Boolean) change.getValueAdded();
                    draggedStart.set(onOff);
                    getProperties().remove("dragged-start");
                } else if (change.getKey().equals(DRAGGEDEND)) {
                    Boolean onOff = (Boolean) change.getValueAdded();
                    draggedEnd.set(onOff);
                    getProperties().remove(DRAGGEDEND);
                } else if (change.getKey().equals(SELECTED)) {
                    Boolean onOff = (Boolean) change.getValueAdded();
                    selected.set(onOff);
                    getProperties().remove(SELECTED);
                } else if (change.getKey().equals(HIDDEN)) {
                    Boolean onOff = (Boolean) change.getValueAdded();
                    setHidden(onOff);
                    getProperties().remove(HIDDEN);
                } else if (change.getKey().equals("control")) {
                    T control = (T) change.getValueAdded();
                    setDateControl(control);
                    getProperties().remove("control");
                }
            }
        };

        getProperties().addListener(propertiesListener);

        dateControlProperty().addListener((observable, oldControl, newControl) -> {
            if (oldControl != null) {
                oldControl.getSelections().removeListener(weakSelectionListener);
                oldControl.draggedEntryProperty().removeListener(weakDraggedListener);
            }
            if (newControl != null) {
                newControl.getSelections().addListener(weakSelectionListener);
                newControl.draggedEntryProperty().addListener(weakDraggedListener);
            }

            bindVisibility();
        });

        addEventHandler(KeyEvent.KEY_PRESSED, evt -> {
            switch (evt.getCode()) {
                case ENTER:
                    Point2D localToScreen = localToScreen(0, 0);
                    showDetails(evt, localToScreen.getX() + getWidth(), localToScreen.getY() + getHeight() / 2);
                    break;
                default:
                    break;
            }
        });

        dateControlProperty().addListener(it -> {
            ObservableSet<Entry<?>> selections = getDateControl().getSelections();
            boolean contains = selections.contains(entry);
            selected.set(contains);
        });

        addEventHandler(MouseEvent.MOUSE_PRESSED, this::performSelection);

        bindEntry(entry);
    }

    /**
     * Returns the calendar entry for which the view was created.
     *
     * @return the calendar entry
     */
    public final Entry<?> getEntry() {
        return entry;
    }

    private InvalidationListener calendarListener = it -> bindVisibility();

    private WeakInvalidationListener weakCalendarListener = new WeakInvalidationListener(calendarListener);

    private void bindEntry(Entry<?> entry) {
        setStartDate(entry.getStartDate());
        setEndDate(entry.getEndDate());
        setStartTime(entry.getStartTime());
        setEndTime(entry.getEndTime());

        if (entry instanceof DraggedEntry) {
            /*
             * We want to make sure the dragged entry gets styled like a
             * selected entry.
             */
            getProperties().put(SELECTED, true);
        }

        entry.calendarProperty().addListener(weakCalendarListener);
    }

    private void bindVisibility() {
        Entry<?> entry = getEntry();
        if (entry != null) {
            Calendar calendar = entry.getCalendar();
            if (calendar != null) {
                visibleProperty().bind(Bindings.and(getDateControl().getCalendarVisibilityProperty(calendar), Bindings.not(hiddenProperty())));
            }
        }
    }

    private boolean _hidden = false;

    private ReadOnlyBooleanWrapper hidden;

    /**
     * A property set internally to indicate that the view could not be shown to
     * the user. Most likely because there wasn't enough space (common case in
     * {@link MonthView} where space is restricted).
     *
     * @return a read-only property used as a flag to signal whether the view is
     * hidden or not
     */
    public final ReadOnlyBooleanProperty hiddenProperty() {
        if (hidden == null) {
            hidden = new ReadOnlyBooleanWrapper(this, HIDDEN, _hidden);
        }
        return hidden.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #hiddenProperty()}.
     *
     * @return true if the view is currently hidden because of insufficient
     * space
     */
    public final boolean isHidden() {
        return hidden == null ? _hidden : hidden.get();
    }

    private void setHidden(boolean b) {
        if (hidden == null) {
            _hidden = b;
        } else {
            hidden.set(b);
        }
    }

    private void processFocus() {
        if (isFocused()) {

            if (!getProperties().containsKey("disable-focus-handling")) {
                DateControl control = getDateControl();
                if (control != null) {
                    if (!control.getSelections().contains(getEntry())) {
                        control.getSelections().clear();
                        control.select(getEntry());
                    }
                }

                bounce();
            }

        }
    }

    /**
     * Makes the entry view "bounce" by applying a scale transition. This is a
     * good way to make an entry stand out, e.g. when it receives the keyboard
     * focus.
     */
    public final void bounce() {
        if (isEnableBounce()) {
            ScaleTransition transition = new ScaleTransition(Duration.millis(200), this);
            setCache(true);
            setCacheHint(CacheHint.SCALE);
            transition.setAutoReverse(true);
            transition.setFromX(1);
            transition.setToX(.8);
            transition.setFromY(1);
            transition.setToY(.8);
            transition.setCycleCount(2);
            transition.setOnFinished(evt -> setCache(false));
            transition.play();
        }
    }

    private final BooleanProperty enableBounce = new SimpleBooleanProperty(this, "enableBounce", false);

    public final boolean isEnableBounce() {
        return enableBounce.get();
    }

    /**
     * Controls whether the entry should use a scale transition to bounce when it receives the
     * focus. The default is false.
     *
     * @return true if the entry should bounce
     */
    public final BooleanProperty enableBounceProperty() {
        return enableBounce;
    }

    public final void setEnableBounce(boolean enableBounce) {
        this.enableBounce.set(enableBounce);
    }

    private InvalidationListener selectionListener = it -> updateSelection();

    private WeakInvalidationListener weakSelectionListener = new WeakInvalidationListener(selectionListener);

    private InvalidationListener draggedListener = it -> updateDragged();

    private WeakInvalidationListener weakDraggedListener = new WeakInvalidationListener(draggedListener);

    private void updateSelection() {
        DateControl control = getDateControl();
        if (control != null) {
            Entry<?> entry = getEntry();

            if (control.getSelections().contains(entry)) {
                selected.set(true);
            } else {
                selected.set(false);
            }
        }
    }

    private void updateDragged() {
        DateControl control = getDateControl();
        if (control != null) {
            DraggedEntry draggedEntry = control.getDraggedEntry();
            if (draggedEntry != null) {
                if (draggedEntry.getOriginalEntry().equals(getEntry())) {
                    switch (draggedEntry.getDragMode()) {
                        case END_TIME:
                            draggedEnd.set(true);
                            break;
                        case START_AND_END_TIME:
                            dragged.set(true);
                            break;
                        case START_TIME:
                            draggedStart.set(true);
                            break;
                        default:
                            break;
                    }
                } else {
                    dragged.set(false);
                    draggedStart.set(false);
                    draggedEnd.set(false);
                }
            } else {
                dragged.set(false);
                draggedStart.set(false);
                draggedEnd.set(false);
            }
        }
    }

    private void showDetails(InputEvent evt, double x, double y) {
        DateControl control = getDateControl();

        /*
         * We might run in the sampler application. Then the entry view will not
         * be inside a date control.
         */
        if (control != null && getParent() != null) {
            Callback<EntryDetailsParameter, Boolean> callback = control.getEntryDetailsCallback();
            EntryDetailsParameter param = new EntryDetailsParameter(evt, control, getEntry(), getParent(), x, y);
            callback.call(param);
        }
    }

    /**
     * An enumerator used for specifying the position of an entry view. A
     * calendar entry can span multiple days. The position can be used if the
     * view is shown on the "first" day, the "last" day, or some day in the
     * "middle" of the span. If the entry is located on only one day then the
     * position will be "only".
     * <p>
     * The image below illustrates this concept:
     *
     * <img src="doc-files/multi-days.png" alt="Multi Days">
     *
     * @see EntryViewBase#positionProperty()
     */
    public enum Position {

        /**
         * Used when the calendar entry spans multiple days and the entry view
         * is the shown on the first day.
         */
        FIRST,

        /**
         * Used when the calendar entry spans multiple days and the entry view
         * is shown on one of the days in the middle.
         */
        MIDDLE,

        /**
         * Used when the calendar entry spans multiple days and the entry view
         * is the shown on the last day.
         */
        LAST,

        /**
         * Used when the calendar entry only spans a single day and the entry
         * view is shown on that day.
         */
        ONLY
    }

    private Position _position = Position.ONLY;

    private ReadOnlyObjectWrapper<Position> position;

    /**
     * A calendar entry can span multiple days. The position can be used if the
     * view is shown on the "first" day, the "last" day, or some day in the
     * "middle" of the span. If the entry is located on only one day then the
     * position will be "only". This property is read-only and will be set by
     * the framework.
     * <p>
     * The image below illustrates this concept:
     *
     * <img src="doc-files/multi-days.png" alt="Multi Day">
     *
     * @return the position of the view within the time range of the calendar
     * entry
     */
    public final ReadOnlyObjectProperty<Position> positionProperty() {
        if (position == null) {
            position = new ReadOnlyObjectWrapper<>(this, "position", _position);
        }
        return position.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #positionProperty()}.
     *
     * @return the position
     */
    public final Position getPosition() {
        return position == null ? _position : position.get();
    }

    private void setPosition(Position pos) {
        if (position == null) {
            _position = pos;
        } else {
            position.set(pos);
        }
    }

    private T _dateControl;

    private ReadOnlyObjectWrapper<T> dateControl;

    /**
     * The date control where the entry view is shown.
     *
     * @return the date control
     */
    public final ReadOnlyObjectProperty<T> dateControlProperty() {
        if (dateControl == null) {
            dateControl = new ReadOnlyObjectWrapper<>(this, "dateControl", _dateControl);
        }

        return dateControl.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #dateControlProperty()}.
     *
     * @return the date control
     */
    public final T getDateControl() {
        return dateControl == null ? _dateControl : dateControl.get();
    }

    private void setDateControl(T control) {
        if (dateControl == null) {
            _dateControl = control;
        } else {
            dateControl.set(control);
        }
    }

    private LocalDate _startDate;

    private ReadOnlyObjectWrapper<LocalDate> startDate;

    /**
     * The date where the view starts to appear (not the start date of the
     * calendar entry). In most views the start and end date of an entry view
     * are the same date (the date shown by the control). However, the
     * {@link AllDayView} lays out entry views across multiple days.
     *
     * @return the start date of the view
     */
    public final ReadOnlyObjectProperty<LocalDate> startDateProperty() {
        if (startDate == null) {
            startDate = new ReadOnlyObjectWrapper<>(this, "startDate", _startDate);
        }
        return startDate.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #startDateProperty()}.
     *
     * @return the start date of the view (not of the calendar entry)
     */
    public final LocalDate getStartDate() {
        return startDate == null ? _startDate : startDate.get();
    }

    private void setStartDate(LocalDate date) {
        if (startDate == null) {
            _startDate = date;
        } else {
            startDate.set(date);
        }
    }

    private LocalDate _endDate;

    private ReadOnlyObjectWrapper<LocalDate> endDate;

    /**
     * The date where the view stops to appear (not the end date of the calendar
     * entry). In most views the start and end date of an entry view are the
     * same date (the date shown by the control). However, the
     * {@link AllDayView} lays out entry views across multiple days.
     *
     * @return the end date of the view
     */
    public final ReadOnlyObjectProperty<LocalDate> endDateProperty() {
        if (endDate == null) {
            endDate = new ReadOnlyObjectWrapper<>(this, "endDate", _endDate);
        }

        return endDate.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #endDateProperty()}.
     *
     * @return the end date of the view (not of the calendar entry)
     */
    public final LocalDate getEndDate() {
        return endDate == null ? _endDate : endDate.get();
    }

    private void setEndDate(LocalDate date) {
        if (endDate == null) {
            _endDate = date;
        } else {
            endDate.set(date);
        }
    }

    private LocalTime _startTime;

    private ReadOnlyObjectWrapper<LocalTime> startTime;

    /**
     * The time where the entry view starts (not the start time of the calendar
     * entry).
     *
     * @return the start time of the view (not of the calendar entry)
     */
    public final ReadOnlyObjectProperty<LocalTime> startTimeProperty() {
        if (startTime == null) {
            startTime = new ReadOnlyObjectWrapper<>(this, "startTime", _startTime);
        }
        return startTime.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #startTimeProperty()}.
     *
     * @return the start time of the view (not of the calendar entry)
     */
    public final LocalTime getStartTime() {
        return startTime == null ? _startTime : startTime.get();
    }

    private void setStartTime(LocalTime time) {
        if (startTime == null) {
            _startTime = time;
        } else {
            startTime.set(time);
        }
    }

    private LocalTime _endTime;

    private ReadOnlyObjectWrapper<LocalTime> endTime;

    /**
     * The time where the entry view ends (not the end time of the calendar
     * entry).
     *
     * @return the end time of the view (not of the calendar entry)
     */
    public final ReadOnlyObjectProperty<LocalTime> endTimeProperty() {
        if (endTime == null) {
            endTime = new ReadOnlyObjectWrapper<>(this, "endTime", _endTime);
        }
        return endTime.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #endTimeProperty()}.
     *
     * @return the end time of the view (not of the calendar entry)
     */
    public final LocalTime getEndTime() {
        return endTime == null ? _endTime : endTime.get();
    }

    private void setEndTime(LocalTime time) {
        if (endTime == null) {
            _endTime = time;
        } else {
            endTime.set(time);
        }
    }

    /*
     * Dragged support.
     */

    private final ReadOnlyBooleanWrapper dragged = new ReadOnlyBooleanWrapper(false) {

        @Override
        protected void invalidated() {
            pseudoClassStateChanged(DRAGGED_PSEUDO_CLASS, get());
        }

        @Override
        public Object getBean() {
            return EntryViewBase.this;
        }

        @Override
        public String getName() {
            return DRAGGED;
        }
    };

    /**
     * A flag used to indicate that the entry view is currently being dragged by
     * the user. This property triggers the pseudo class "dragged".
     *
     * @return true if the entry is being dragged
     */
    public final ReadOnlyBooleanProperty draggedProperty() {
        return dragged.getReadOnlyProperty();
    }

    /**
     * Returns the value of the {@link #draggedProperty()}.
     *
     * @return true if the entry is being dragged
     */
    public final boolean isDragged() {
        return draggedProperty().get();
    }

    /*
     * Dragged start support.
     */

    private final ReadOnlyBooleanWrapper draggedStart = new ReadOnlyBooleanWrapper(false) {

        @Override
        protected void invalidated() {
            pseudoClassStateChanged(DRAGGED_START_PSEUDO_CLASS, get());
        }

        @Override
        public Object getBean() {
            return EntryViewBase.this;
        }

        @Override
        public String getName() {
            return "draggedStart";
        }
    };

    /**
     * A flag used to indicate that the user is currently changing the start
     * time of the entry (view). This property triggers the pseudo class
     * "dragged-start".
     *
     * @return true if the entry start time is being changed
     */
    public final ReadOnlyBooleanProperty draggedStartProperty() {
        return draggedStart.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #draggedStartProperty()}.
     *
     * @return true if the start time is changing
     */
    public final boolean isDraggedStart() {
        return draggedStartProperty().get();
    }

    /*
     * Dragged end support.
     */

    private final ReadOnlyBooleanWrapper draggedEnd = new ReadOnlyBooleanWrapper(false) {

        @Override
        protected void invalidated() {
            pseudoClassStateChanged(DRAGGED_END_PSEUDO_CLASS, get());
        }

        @Override
        public Object getBean() {
            return EntryViewBase.this;
        }

        @Override
        public String getName() {
            return "draggedEnd";
        }
    };

    /**
     * A flag used to indicate that the user is currently changing the end time
     * of the entry (view). This property triggers the pseudo class
     * "dragged-end".
     *
     * @return true if the entry end time is being changed
     */
    public final ReadOnlyBooleanProperty draggedEndProperty() {
        return draggedEnd.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #draggedEndProperty()}.
     *
     * @return true if the end time is changing
     */
    public final boolean isDraggedEnd() {
        return draggedEndProperty().get();
    }

    /*
     * Selected support.
     */

    private final ReadOnlyBooleanWrapper selected = new ReadOnlyBooleanWrapper(false) {

        @Override
        protected void invalidated() {
            pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, get());
        }

        @Override
        public Object getBean() {
            return EntryViewBase.this;
        }

        @Override
        public String getName() {
            return SELECTED;
        }
    };

    /**
     * A flag used to indicate that the entry has been selected by the user.
     * This property triggers the "selected" pseudo class.
     *
     * @return true if the entry view has been selected
     * @see DateControl#getSelections()
     */
    public final ReadOnlyBooleanProperty selectedProperty() {
        return selected.getReadOnlyProperty();
    }

    /**
     * Returns the value of {@link #selectedProperty()}.
     *
     * @return true if the entry view is selected
     */
    public final boolean isSelected() {
        return selectedProperty().get();
    }


    /**
     * Different strategies for determining the height of an entry view. Normally
     * the height of an entry is based on its start and end times. But sometimes
     * we might want to simply use the start time for its location and the required
     * height based on its content (e.g. the labels inside the entry view). The layout
     * strategy {@link HeightLayoutStrategy#COMPUTE_PREF_SIZE} disables changes to the end
     * time of the entry as the bottom y coordiante of the view would not accurately
     * represent the end time of the entry.
     */
    public enum HeightLayoutStrategy {
        USE_START_AND_END_TIME,
        COMPUTE_PREF_SIZE,
    }

    private final ObjectProperty<HeightLayoutStrategy> heightLayoutStrategy = new SimpleObjectProperty<>(this, "heightLayoutStrategy", HeightLayoutStrategy.USE_START_AND_END_TIME);

    public final HeightLayoutStrategy getHeightLayoutStrategy() {
        return heightLayoutStrategy.get();
    }

    /**
     * Stores the height layout strategy that will be used for this entry view. For
     * more information see {@link HeightLayoutStrategy}.
     *
     * @return the entry view's height layout strategy
     */
    public final ObjectProperty<HeightLayoutStrategy> heightLayoutStrategyProperty() {
        return heightLayoutStrategy;
    }

    public final void setHeightLayoutStrategy(HeightLayoutStrategy heightLayoutStrategy) {
        this.heightLayoutStrategy.set(heightLayoutStrategy);
    }

    /**
     * Different strategies for aligning the entry view inside its day view. Normally
     * an entry view fills the entire width of a {@link DayView} but special cases might
     * require the entry to simply use the preferred width of the view and align the
     * entry's view on the left, the center, or the middle.
     */
    public enum AlignmentStrategy {
        FILL,
        ALIGN_LEFT,
        ALIGN_RIGHT,
        ALIGN_CENTER
    }

    private final ObjectProperty<AlignmentStrategy> alignmentStrategy = new SimpleObjectProperty<>(this, "alignmentStrategy", AlignmentStrategy.FILL);

    public final AlignmentStrategy getAlignmentStrategy() {
        return alignmentStrategy.get();
    }

    /**
     * Stores the alignment strategy that will be used for this entry view. For
     * more information see {@link AlignmentStrategy}.
     *
     * @return the entry view's alignment strategy
     */
    public final ObjectProperty<AlignmentStrategy> alignmentStrategyProperty() {
        return alignmentStrategy;
    }

    public final void setAlignmentStrategy(AlignmentStrategy alignmentStrategy) {
        this.alignmentStrategy.set(alignmentStrategy);
    }

    /**
     * Convenience method to check if this entry view intersects with the given
     * entry view. Delegates to {@link Entry#intersects(Entry)}.
     *
     * @param otherView the other view to check
     * @return true if the time intervals of the two entries / views overlap each other
     */
    public final boolean intersects(EntryViewBase<?> otherView) {
        return getEntry().intersects(otherView.getEntry());
    }

    /**
     * Convenience method to determine whether the entry belongs to a calendar
     * that is read-only.
     *
     * @return true if the entry can not be edited by the user
     */
    public final boolean isReadOnly() {
        Entry<?> entry = getEntry();
        Calendar calendar = entry.getCalendar();
        if (calendar != null) {
            return calendar.isReadOnly();
        }

        return false;
    }

    @Override
    public int compareTo(EntryViewBase<T> o) {
        return getEntry().compareTo(o.getEntry());
    }

    @Override
    public String toString() {
        return "EntryViewBase [entry=" + getEntry() + ", selected="
                + isSelected() + "]";
    }

    private static final String ENTRY_VIEW_CATEGORY = "Entry View Base";

    /**
     * Returns a list of property items that can be shown by the
     * {@link PropertySheet} of ControlsFX.
     *
     * @return the property sheet items
     */
    public ObservableList<Item> getPropertySheetItems() {

        ObservableList<Item> items = FXCollections.observableArrayList();

        items.add(new Item() {

            @Override
            public Optional<ObservableValue<?>> getObservableValue() {
                return Optional.of(positionProperty());
            }

            @Override
            public void setValue(Object value) {
                position.set((Position) value);
            }

            @Override
            public Object getValue() {
                return getPosition();
            }

            @Override
            public Class<?> getType() {
                return Position.class;
            }

            @Override
            public String getName() {
                return "Position";
            }

            @Override
            public String getDescription() {
                return "Position (first, last, middle, only)";
            }

            @Override
            public String getCategory() {
                return ENTRY_VIEW_CATEGORY;
            }
        });

        items.add(new Item() {

            @Override
            public Optional<ObservableValue<?>> getObservableValue() {
                return Optional.of(selectedProperty());
            }

            @Override
            public void setValue(Object value) {
                selected.set((boolean) value);
            }

            @Override
            public Object getValue() {
                return isSelected();
            }

            @Override
            public Class<?> getType() {
                return Boolean.class;
            }

            @Override
            public String getName() {
                return "Selected";
            }

            @Override
            public String getDescription() {
                return "Selected";
            }

            @Override
            public String getCategory() {
                return ENTRY_VIEW_CATEGORY;
            }
        });

        return items;
    }

    private void performSelection(MouseEvent evt) {
        if ((evt.getButton().equals(PRIMARY) || evt.isPopupTrigger()) && evt.getClickCount() == 1) {
            String disableFocusHandlingKey = "disable-focus-handling";
            getProperties().put(disableFocusHandlingKey, true);
            requestFocus();

            DateControl control = getDateControl();

            if (control == null) {
                return;
            }

            if (!isMultiSelect(evt) && !control.getSelections().contains(entry)) {
                control.clearSelection();
            }

            if (isMultiSelect(evt) && control.getSelections().contains(entry)) {
                control.deselect(entry);
            } else {
                control.getSelections().add(entry);
            }

            getProperties().remove(disableFocusHandlingKey);
        }
    }

    private boolean isMultiSelect(MouseEvent evt) {
        return (evt.isShiftDown() || evt.isShortcutDown()) && getDateControl().getSelectionMode().equals(MULTIPLE);
    }

}
