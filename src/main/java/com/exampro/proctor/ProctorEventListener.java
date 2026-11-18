package com.exampro.proctor;

import com.exampro.model.entity.ProctorEvent;

public interface ProctorEventListener {
    void onEvent(ProctorEvent event);
}
