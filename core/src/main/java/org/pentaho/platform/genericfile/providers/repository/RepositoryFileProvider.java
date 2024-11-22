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


package org.pentaho.platform.genericfile.providers.repository;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.pentaho.platform.api.genericfile.GenericFilePath;
import org.pentaho.platform.api.genericfile.GenericFilePermission;
import org.pentaho.platform.api.genericfile.GetTreeOptions;
import org.pentaho.platform.api.genericfile.exception.AccessControlException;
import org.pentaho.platform.api.genericfile.exception.InvalidPathException;
import org.pentaho.platform.api.genericfile.exception.NotFoundException;
import org.pentaho.platform.api.genericfile.exception.OperationFailedException;
import org.pentaho.platform.api.genericfile.model.IGenericFileContentWrapper;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFilePermission;
import org.pentaho.platform.api.repository2.unified.UnifiedRepositoryAccessDeniedException;
import org.pentaho.platform.api.repository2.unified.webservices.RepositoryFileDto;
import org.pentaho.platform.api.repository2.unified.webservices.RepositoryFileTreeDto;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.genericfile.BaseGenericFileProvider;
import org.pentaho.platform.genericfile.messages.Messages;
import org.pentaho.platform.genericfile.model.DefaultGenericFileContentWrapper;
import org.pentaho.platform.genericfile.providers.repository.model.RepositoryFile;
import org.pentaho.platform.genericfile.providers.repository.model.RepositoryFileTree;
import org.pentaho.platform.genericfile.providers.repository.model.RepositoryFolder;
import org.pentaho.platform.genericfile.providers.repository.model.RepositoryObject;
import org.pentaho.platform.repository2.unified.fileio.RepositoryFileInputStream;
import org.pentaho.platform.repository2.unified.fileio.RepositoryFileOutputStream;
import org.pentaho.platform.repository2.unified.webservices.DefaultUnifiedRepositoryWebService;
import org.pentaho.platform.web.http.api.resources.services.FileService;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.Objects;

import static org.pentaho.platform.util.RepositoryPathEncoder.encodeRepositoryPath;

public class RepositoryFileProvider extends BaseGenericFileProvider<RepositoryFile> {
  public static final String ROOT_PATH = "/";

  // Ignore Sonar rule regarding field name convention. There's no way to mark the field as final due to lazy
  // initialization. Correctly using the name convention for constants.
  private static GenericFilePath ROOT_GENERIC_PATH;

  static {
    try {
      ROOT_GENERIC_PATH = GenericFilePath.parseRequired( ROOT_PATH );
    } catch ( InvalidPathException e ) {
      // Never happens.
    }
  }

  public static final String TYPE = "repository";

  @NonNull
  private final IUnifiedRepository unifiedRepository;

  /**
   * The file service wraps a unified repository and provides additional functionality.
   */
  @NonNull
  private final FileService fileService;

  // TODO: Actually fix the base FileService class to do this and eliminate this class when available on the platform.
  /**
   * Custom {@code FileService} class that ensures that the contained repository web service uses the specified unified
   * repository instance. The methods {@code getRepositoryFileInputStream} and {@code getRepositoryFileOutputStream}
   * also do not pass the correct repository instance forward.
   */
  private static class CustomFileService extends FileService {
    public CustomFileService( @NonNull IUnifiedRepository repository ) {
      this.repository = Objects.requireNonNull( repository );
    }

    @Override
    protected DefaultUnifiedRepositoryWebService getRepoWs() {
      if ( defaultUnifiedRepositoryWebService == null ) {
        defaultUnifiedRepositoryWebService = new DefaultUnifiedRepositoryWebService( repository );
      }

      return defaultUnifiedRepositoryWebService;
    }

    @Override
    public RepositoryFileOutputStream getRepositoryFileOutputStream( String path ) {
      return new RepositoryFileOutputStream( path, false, false, repository, false );
    }

    @Override
    public RepositoryFileInputStream getRepositoryFileInputStream(
      org.pentaho.platform.api.repository2.unified.RepositoryFile repositoryFile ) throws FileNotFoundException {
      return new RepositoryFileInputStream( repositoryFile, repository );
    }
  }

  public RepositoryFileProvider() {
    this( PentahoSystem.get( IUnifiedRepository.class, PentahoSessionHolder.getSession() ) );
  }

  public RepositoryFileProvider( @NonNull IUnifiedRepository unifiedRepository ) {
    this( unifiedRepository, new CustomFileService( unifiedRepository ) );
  }

  public RepositoryFileProvider( @NonNull IUnifiedRepository unifiedRepository,
                                 @NonNull FileService fileService ) {
    this.unifiedRepository = Objects.requireNonNull( unifiedRepository );
    this.fileService = Objects.requireNonNull( fileService );
  }

  @NonNull
  @Override
  public Class<RepositoryFile> getFileClass() {
    return RepositoryFile.class;
  }

  @NonNull
  @Override
  public String getName() {
    return Messages.getString( "GenericFileRepository.REPOSITORY_FOLDER_DISPLAY" );
  }

  @NonNull
  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  protected boolean createFolderCore( @NonNull GenericFilePath path ) throws OperationFailedException {
    // When parent path is not found, its creation is attempted.
    try {
      return fileService.doCreateDirSafe( encodeRepositoryPath( path.toString() ) );
    } catch ( UnifiedRepositoryAccessDeniedException e ) {
      throw new AccessControlException( e );
    } catch ( FileService.InvalidNameException e ) {
      throw new InvalidPathException();
    }
  }

  @NonNull
  protected RepositoryFileTree getTreeCore( @NonNull GetTreeOptions options ) throws NotFoundException {

    // Get the whole tree under the provider root (VFS connections)?
    GenericFilePath basePath = options.getBasePath();
    if ( basePath == null ) {
      basePath = ROOT_GENERIC_PATH;
      assert basePath != null;
    } else if ( !owns( basePath ) ) {
      throw new NotFoundException( String.format( "Base path not found '%s'.", basePath ) );
    }

    String repositoryFilterString = getRepositoryFilter( options.getFilter() );

    RepositoryFileTreeDto nativeTree = fileService.doGetTree(
      encodeRepositoryPath( basePath.toString() ),
      options.getMaxDepth(),
      repositoryFilterString,
      options.getShowHiddenFiles(),
      false,
      false );

    // The parent path of base path.
    GenericFilePath parentPath = basePath.getParent();
    String parentPathString = parentPath != null ? parentPath.toString() : null;

    RepositoryFileTree tree = convertToTreeNode( nativeTree, parentPathString );

    RepositoryFolder repositoryFolder = (RepositoryFolder) tree.getFile();
    repositoryFolder.setName( Messages.getString( "GenericFileRepository.REPOSITORY_FOLDER_DISPLAY" ) );
    repositoryFolder.setCanAddChildren( false );
    repositoryFolder.setCanDelete( false );
    repositoryFolder.setCanEdit( false );

    return tree;
  }

  @Override public IGenericFileContentWrapper getFileContentWrapper( @NonNull GenericFilePath path )
    throws OperationFailedException {
    org.pentaho.platform.api.repository2.unified.RepositoryFile repositoryFile =
      unifiedRepository.getFile( path.toString() );
    try {
      RepositoryFileInputStream inputStream = fileService.getRepositoryFileInputStream( repositoryFile );

      String fileName = repositoryFile.getName();
      String mimeType = inputStream.getMimeType();

      return new DefaultGenericFileContentWrapper( inputStream, fileName, mimeType );
    } catch ( FileNotFoundException e ) {
      throw new OperationFailedException( e );
    }
  }

  /**
   * Get the tree filter's corresponding repository filter
   *
   * @param treeFilter
   * @return
   */
  protected String getRepositoryFilter( GetTreeOptions.TreeFilter treeFilter ) {
    switch ( treeFilter ) {
      case FOLDERS:
        return "*|FOLDERS";
      case FILES:
        return "*|FILES";
      case ALL:
      default:
        return "*";
    }
  }

  @Override
  public boolean doesFolderExist( @NonNull GenericFilePath path ) {
    org.pentaho.platform.api.repository2.unified.RepositoryFile file = unifiedRepository.getFile( path.toString() );
    return file != null && file.isFolder();
  }

  @NonNull
  private RepositoryObject convert( @NonNull RepositoryFileDto nativeFile, @Nullable String parentPath ) {

    RepositoryObject repositoryObject = nativeFile.isFolder() ? new RepositoryFolder() : new RepositoryFile();

    repositoryObject.setPath( nativeFile.getPath() );
    repositoryObject.setName( nativeFile.getName() );
    repositoryObject.setParentPath( parentPath );
    repositoryObject.setHidden( nativeFile.isHidden() );
    Date modifiedDate = ( nativeFile.getLastModifiedDate() != null && !nativeFile.getLastModifiedDate().isEmpty() )
      ? new Date( Long.parseLong( nativeFile.getLastModifiedDate() ) )
      : new Date( Long.parseLong( nativeFile.getCreatedDate() ) );
    repositoryObject.setModifiedDate( modifiedDate );
    repositoryObject.setObjectId( nativeFile.getId() );
    repositoryObject.setCanEdit( true );
    repositoryObject.setTitle( nativeFile.getTitle() );
    repositoryObject.setDescription( nativeFile.getDescription() );
    if ( nativeFile.isFolder() ) {
      convertFolder( (RepositoryFolder) repositoryObject, nativeFile );
    }

    return repositoryObject;
  }

  private void convertFolder( @NonNull RepositoryFolder folder, RepositoryFileDto nativeFile ) {
    folder.setCanAddChildren( true );
  }

  @NonNull
  private RepositoryFileTree convertToTreeNode( @NonNull RepositoryFileTreeDto nativeTree,
                                                @Nullable String parentPath ) {

    RepositoryObject repositoryObject = convert( nativeTree.getFile(), parentPath );
    RepositoryFileTree repositoryTree = new RepositoryFileTree( repositoryObject );

    if ( nativeTree.getChildren() != null ) {
      // Ensure an empty list is reflected.
      repositoryTree.setChildren( new ArrayList<>() );

      String path = repositoryObject.getPath();

      for ( RepositoryFileTreeDto nativeChildTree : nativeTree.getChildren() ) {
        repositoryTree.addChild( convertToTreeNode( nativeChildTree, path ) );
      }
    }

    return repositoryTree;
  }

  @Override
  public boolean owns( @NonNull GenericFilePath path ) {
    return path.getFirstSegment().equals( ROOT_PATH );
  }

  @Override
  public boolean hasAccess( @NonNull GenericFilePath path, @NonNull EnumSet<GenericFilePermission> permissions ) {
    return unifiedRepository.hasAccess( path.toString(), getRepositoryPermissions( permissions ) );

  }

  private EnumSet<RepositoryFilePermission> getRepositoryPermissions( EnumSet<GenericFilePermission> permissions ) {
    EnumSet<RepositoryFilePermission> repositoryFilePermissions = EnumSet.noneOf( RepositoryFilePermission.class );
    for( GenericFilePermission permission: permissions ) {
      switch ( permission ) {
        case READ:
          repositoryFilePermissions.add( RepositoryFilePermission.READ );
          break;
        case WRITE:
          repositoryFilePermissions.add( RepositoryFilePermission.WRITE );
          break;
        case ALL:
          repositoryFilePermissions.add( RepositoryFilePermission.ALL );
          break;
        case DELETE:
          repositoryFilePermissions.add( RepositoryFilePermission.DELETE );
          break;
        case ACL_MANAGEMENT:
          repositoryFilePermissions.add( RepositoryFilePermission.ACL_MANAGEMENT );
          break;
      }
    }
    return repositoryFilePermissions;
  }
}
