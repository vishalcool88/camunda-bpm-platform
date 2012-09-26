package com.camunda.fox.cycle.connector.svn;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.tigris.subversion.svnclientadapter.ISVNClientAdapter;
import org.tigris.subversion.svnclientadapter.ISVNDirEntry;
import org.tigris.subversion.svnclientadapter.SVNNodeKind;
import org.tigris.subversion.svnclientadapter.SVNRevision;
import org.tigris.subversion.svnclientadapter.SVNUrl;
import org.tigris.subversion.svnclientadapter.svnkit.SvnKitClientAdapter;

import com.camunda.fox.cycle.connector.Connector;
import com.camunda.fox.cycle.connector.ConnectorNode;
import com.camunda.fox.cycle.connector.ConnectorNodeType;
import com.camunda.fox.cycle.connector.ContentInformation;
import com.camunda.fox.cycle.connector.Secured;
import com.camunda.fox.cycle.connector.Threadsafe;
import com.camunda.fox.cycle.entity.ConnectorConfiguration;
import com.camunda.fox.cycle.exception.CycleException;


public class SvnConnector extends Connector {
  
  public final static String CONFIG_KEY_REPOSITORY_PATH = "repositoryPath";
  public final static String CONFIG_KEY_TEMPORARY_FILE_STORE = "temporaryFileStore";
  public final static String DEFAULT_CONFIG_KEY_TEMPORARY_FILE_STORE = "/tmp";
  
  private static final String SLASH_CHAR = "/";
  
  private static Logger logger = Logger.getLogger(SvnConnector.class.getName());
  
  private String baseTemporaryFileStore;
  private String baseUrl;
  
  private ISVNClientAdapter svnClientAdapter;
  private ReentrantLock transactionLock = new ReentrantLock();

  @Override
  public void init(ConnectorConfiguration config) {
    if (svnClientAdapter == null) {
      System.setProperty("svnkit.http.methods","Basic,NTLM");
      svnClientAdapter = new SvnKitClientAdapter();
    }
    
    baseTemporaryFileStore = config.getProperties().get(CONFIG_KEY_TEMPORARY_FILE_STORE);
    
    if (baseTemporaryFileStore == null) {
      baseTemporaryFileStore = DEFAULT_CONFIG_KEY_TEMPORARY_FILE_STORE;
    }
    // Load temporary file store path from system property, e.g. ${user.home} if it is passed
    if (baseTemporaryFileStore.matches("\\$\\{.*\\}")) {
      String systemProperty = baseTemporaryFileStore.substring(2, baseTemporaryFileStore.length() - 1);
      String systemPropertyValue = System.getProperty(baseTemporaryFileStore.substring(2, baseTemporaryFileStore.length() - 1));
      if (systemPropertyValue != null) {
        try {
          baseTemporaryFileStore = new File(systemPropertyValue).toURI().toString();
          logger.info("Loading temporary file store path from system property " + systemProperty + ": " + baseTemporaryFileStore);
        } catch (Exception e) {
          logger.log(Level.WARNING, "Could not read temporary file store path from system property "+  baseTemporaryFileStore);
          baseTemporaryFileStore = DEFAULT_CONFIG_KEY_TEMPORARY_FILE_STORE;
        }
      }
    }
    baseUrl = getConfiguration().getProperties().get(CONFIG_KEY_REPOSITORY_PATH);
  }
  
  @Threadsafe
  @Override
  public void login(String userName, String password) {
    svnClientAdapter.setUsername(userName);
    svnClientAdapter.setPassword(password);
  }
  
  private SVNUrl createSvnUrl(ConnectorNode node) throws Exception {
    return createSvnUrl(node.getId());
  }
  
  private SVNUrl createSvnUrl(String id) throws Exception {
    if (!baseUrl.endsWith("/") && !id.startsWith("/")) {
      id = "/" + id;
    }
    String result = baseUrl + id;
    if (result.endsWith("//")) {
      result = result.substring(0, result.length() - 1);
    }
    return new SVNUrl(result);
  }

  @Threadsafe
  @Secured
  @Override
  public List<ConnectorNode> getChildren(ConnectorNode parent) {
    try {
      List<ConnectorNode> nodes = new ArrayList<ConnectorNode>();
      SVNUrl svnUrl = createSvnUrl(parent);
      ISVNDirEntry[] entries = svnClientAdapter.getList(svnUrl, SVNRevision.HEAD, false);
      for (ISVNDirEntry currentEntry : entries) {
        String id = parent.getId();
        if (!id.endsWith(SLASH_CHAR)) {
          id = id + SLASH_CHAR;
        }
        id = id + currentEntry.getPath();
        
        ConnectorNode newNode = new ConnectorNode(id);
        decorateConnectorNode(newNode, currentEntry);
        
        nodes.add(newNode);
      }
      return nodes;
    } catch (Exception e) {
      logger.log(Level.FINER, "Cannot get children for node " + parent.getId(), e);
      throw new CycleException("Children for SVN connector '" + getConfiguration().getLabel() + "' could not be loaded in repository '" + parent.getId() + "'.", e);
    }
    
  }
  
  @Secured
  @Override
  public ConnectorNode getRoot() {
    ConnectorNode rootNode = new ConnectorNode("/", "/");
    rootNode.setType(ConnectorNodeType.FOLDER);
    return rootNode;
  }

  @Threadsafe
  @Secured
  @Override
  public ConnectorNode getNode(String id) {
    try {
      SVNUrl svnUrl = createSvnUrl(id);
      ISVNDirEntry entry = svnClientAdapter.getDirEntry(svnUrl, SVNRevision.HEAD);
      
      if (entry != null) {
        ConnectorNode node = new ConnectorNode(id);
        return decorateConnectorNode(node, entry);
      }
    } catch (Exception e) {
      logger.log(Level.FINER, "Cannot get node '" + id + "' in Svn '" + getConfiguration().getId() + "'.", e);
    }
    return null;
  }
  
  private ConnectorNode decorateConnectorNode(ConnectorNode node, ISVNDirEntry entry) {
    node.setLabel(entry.getPath());
    node.setLastModified(entry.getLastChangedDate());
    node.setConnectorId(getConfiguration().getId());
    node.setType(extractFileType(entry));
    
    return node;
  }
  
  private ConnectorNodeType extractFileType(ISVNDirEntry entry) {
    if (entry.getNodeKind() != SVNNodeKind.FILE) {
      // TODO: What is with the other types?
      return ConnectorNodeType.FOLDER;
    }
    
    String name = entry.getPath();
    if (name.endsWith(".xml") || name.endsWith(".bpmn")) {
      return ConnectorNodeType.BPMN_FILE;
    } else 
    if (name.endsWith(".png")) {
      return ConnectorNodeType.PNG_FILE;
    } else {
      return ConnectorNodeType.ANY_FILE;
    }
  }

  @Threadsafe
  @Secured
  @Override
  public ConnectorNode createNode(String parentId, String id, String label, ConnectorNodeType type) {
    try {
      
      if (type == null || type == ConnectorNodeType.UNSPECIFIED) {
        throw new IllegalArgumentException("Must specify a valid node type");
      }
      
      beginTransaction();
      
      String parentFolder = extractParentFolder(id);
      File temporaryFileStore = getTemporaryFileStore(parentFolder + File.separator + UUID.randomUUID().toString());
      
      SVNUrl svnUrl = createSvnUrl(parentFolder);
      checkout(svnUrl, temporaryFileStore);
      
      File newFile = new File(temporaryFileStore + File.separator + label);
      if (type != ConnectorNodeType.FOLDER) {
        newFile.createNewFile();
        svnClientAdapter.addFile(newFile);
      } else if (type == ConnectorNodeType.FOLDER) {
        newFile.mkdir();
        svnClientAdapter.addDirectory(newFile, true);
      }
      
      commit(new File[] {temporaryFileStore}, "Created node '" + label + "' in '" + parentFolder + "' using camunda fox cycle.");
      
      stopTransaction();
      
      deleteRecursively(temporaryFileStore);
      
      return new ConnectorNode(id, label, type);
    } catch (Exception e) {
      logger.log(Level.FINER, "Error while creating node '" + label + "'.", e);
      throw new CycleException(e);
    }
  }

  @Threadsafe
  @Secured
  @Override
  public void deleteNode(ConnectorNode node) {
    String id = node.getId();
    try {
      SVNUrl svnUrl = createSvnUrl(id);
      svnClientAdapter.remove(new SVNUrl[] {svnUrl}, "Removed '" + id + "' using camunda fox cycle.");
    } catch (Exception e) {
      logger.log(Level.FINER, "Error while deleting node '" + id + "'.", e);
      throw new CycleException(e);
    }
  }

  @Threadsafe
  @Secured
  @Override
  public ContentInformation updateContent(ConnectorNode node, InputStream newContent) {
    try {
      beginTransaction();
      
      File temporaryFileStore = getTemporaryFileStore(UUID.randomUUID().toString());
      String parentFolderId = extractParentFolder(node);
      
      SVNUrl svnUrl = createSvnUrl(parentFolderId);
      checkout(svnUrl, temporaryFileStore);
      
      File file = new File(temporaryFileStore.getAbsolutePath() + File.separator + node.getLabel());
      
      BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
      IOUtils.copy(newContent, bos);
      bos.flush();
      bos.close();
      
      commit(new File[] {temporaryFileStore}, "Updated file '" + node.getLabel() + "' in '" + parentFolderId + "' using camunda fox cycle");
      
      stopTransaction();
      
      ContentInformation result = new ContentInformation(true, new Date(file.lastModified())); 
      deleteRecursively(temporaryFileStore);
      return result;
    } catch (Exception e) {
      stopTransaction();
      logger.log(Level.FINER, "Error while updating file '" + node.getLabel() + "' in '" + extractParentFolder(node) + "'.", e);
      throw new CycleException(e);
    }
  }
  
  private void beginTransaction() {
    transactionLock.lock();
  }
  
  private void stopTransaction() {
    while (true) {
      try {
        transactionLock.unlock();
      } catch (Exception e) {
        break;
      }
    }
  }
  
  private File getTemporaryFileStore(String withSubFolder) {
    return new File(baseTemporaryFileStore + File.separator + withSubFolder);
  }
  
  private void checkout(SVNUrl source, File target) {
    try {
      svnClientAdapter.checkout(source, target, SVNRevision.HEAD, false);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Could not checkout from svn repository '" + source + "' to the following destination '" + target.getAbsolutePath() + "'.", e);
      throw new CycleException(e);
    }
  }
  
  private void commit(File[] sources, String comment) {
    try {
      svnClientAdapter.commit(sources, comment, true);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Could not commit changes in '" + sources[0] + "'.", e);
      throw new CycleException(e);
    }
  }

  private String extractParentFolder(ConnectorNode node) {
    return extractParentFolder(node.getId());
  }
  
  private String extractParentFolder(String nodeId) {
    String parentFolderId = "";
    if (nodeId.contains("/")) {
      parentFolderId = nodeId.substring(0, nodeId.lastIndexOf("/"));
    }
    return parentFolderId;
  }
  
  private boolean deleteRecursively(File file) {
    if (!file.exists()) {
      return false;
    }
    if (file.isFile()) {
      return file.delete();
    }
    boolean result = true;
    File[] children = file.listFiles();
    for (int i = 0; i < children.length; i++) {
      result &= deleteRecursively(children[i]);
    }
    result &= file.delete();
    return result;
  }

  @Threadsafe
  @Secured
  @Override
  public InputStream getContent(ConnectorNode node) {
    try {
      return svnClientAdapter.getContent(createSvnUrl(getPath(node)), SVNRevision.HEAD);
    } catch (Exception e) {
      if (node.getType() == ConnectorNodeType.PNG_FILE) {
        return null;
      }
      throw new CycleException(e);
    }
  }
  
  private String getPath(ConnectorNode node) {
    String path = node.getId();
    switch (node.getType()) {
    case PNG_FILE:
      int pointIndex = path.lastIndexOf(".");
      return path.substring(0, pointIndex) + ".png";
    default: 
      return path;
    }
  }
  
  @Threadsafe
  @Secured
  @Override
  public ContentInformation getContentInformation(ConnectorNode node) {
    ConnectorNode reloadedNode = null;
    try {
      // Try to load the current state (last modified date etc.) of the assigned node.
      reloadedNode = getNode(node.getId());
    } catch (Exception e) {
      return ContentInformation.notFound();
    }
    if (reloadedNode == null) {
      return ContentInformation.notFound();
    }
    if (reloadedNode.getType() == ConnectorNodeType.FOLDER) {
      throw new IllegalArgumentException("Can only get content information from files"); 
    }
    return new ContentInformation(true, reloadedNode.getLastModified());
  }
}
