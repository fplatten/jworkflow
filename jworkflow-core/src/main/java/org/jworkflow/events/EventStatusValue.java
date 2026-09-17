package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public enum EventStatusValue {
    PENDING,
    SUCCESSFUL,
    FAILED,
    RETRYING,
    DEAD_LETTERED
}
