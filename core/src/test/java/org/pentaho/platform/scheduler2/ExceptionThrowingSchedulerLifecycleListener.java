/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/
package org.pentaho.platform.scheduler2;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.engine.IPluginLifecycleListener;
import org.pentaho.platform.api.engine.PluginLifecycleException;

public class ExceptionThrowingSchedulerLifecycleListener  implements IPluginLifecycleListener {
    private Log logger = LogFactory.getLog( ExceptionThrowingSchedulerLifecycleListener .class );

    @Override
    public void init() throws PluginLifecycleException {
        throw new PluginLifecycleException( " exception while initializing" );
    }

    @Override
    public void loaded() throws PluginLifecycleException {

    }

    @Override
    public void unLoaded() throws PluginLifecycleException {
        throw new PluginLifecycleException( " exception while unloading" );
    }
}
