#!/usr/bin/env node
/**
 * jargraph launcher
 *
 * 负责：
 * 1. 查找 JAR 包
 * 2. 探测本地 Java / Maven 环境（优先环境变量，次常见路径）
 * 3. 将探测结果注入环境变量后启动 Kotlin 进程
 *
 * 跨平台：支持 macOS / Linux / Windows
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const isWin = process.platform === 'win32';
const JAR_NAME = 'jar-graph-0.1.0-SNAPSHOT.jar';
const USER_JAR = path.join(os.homedir(), '.jargraph', 'jargraph.jar');

/** Windows 可执行扩展名 */
const WIN_EXTS = ['.exe', '.cmd', '.bat'];

function findJar() {
  const localTarget = path.resolve(process.cwd(), 'target', JAR_NAME);
  if (fs.existsSync(localTarget)) return localTarget;

  const bundled = path.resolve(__dirname, '..', 'target', JAR_NAME);
  if (fs.existsSync(bundled)) return bundled;

  const distBundled = path.resolve(__dirname, '..', 'dist', 'jargraph.jar');
  if (fs.existsSync(distBundled)) return distBundled;

  if (fs.existsSync(USER_JAR)) return USER_JAR;

  return null;
}

function findJava() {
  // 1. JAVA_HOME
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const candidate = path.join(javaHome, 'bin', execName('java'));
    if (fs.existsSync(candidate)) return candidate;
  }

  // 2. PATH
  const fromPath = which('java');
  if (fromPath) return fromPath;

  // 3. 常见安装路径
  const candidates = isWin
    ? [
        path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Java'),
        path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Eclipse Adoptium'),
        path.join(process.env.LOCALAPPDATA || '', 'Programs', 'Eclipse Adoptium'),
      ]
    : [
        '/usr/bin/java',
        '/usr/local/bin/java',
        '/opt/homebrew/bin/java',
        path.join(os.homedir(), '.sdkman', 'candidates', 'java', 'current', 'bin', 'java'),
      ];

  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }

  return null;
}

function findMvn() {
  // 1. 显式环境变量
  const explicit = process.env.JARGRAPH_MVN;
  if (explicit && fs.existsSync(explicit)) return explicit;

  // 2. M2_HOME / MAVEN_HOME
  for (const key of ['M2_HOME', 'MAVEN_HOME']) {
    const home = process.env[key];
    if (home) {
      const candidate = path.join(home, 'bin', execName('mvn'));
      if (fs.existsSync(candidate)) return candidate;
    }
  }

  // 3. PATH
  const fromPath = which('mvn') || which('mvnw');
  if (fromPath) return fromPath;

  // 4. 常见安装路径
  const candidates = isWin
    ? [
        path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Apache Software Foundation', 'Maven', 'bin', 'mvn.cmd'),
        path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Maven', 'bin', 'mvn.cmd'),
        path.join(os.homedir(), 'scoop', 'apps', 'maven', 'current', 'bin', 'mvn.cmd'),
      ]
    : [
        '/usr/local/bin/mvn',
        '/opt/homebrew/bin/mvn',
        '/usr/bin/mvn',
        path.join(os.homedir(), 'Work', 'environments', 'apache-maven-3.6.3', 'bin', 'mvn'),
      ];

  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }

  return null;
}

/** 根据平台补全可执行文件名（Windows 加 .exe/.cmd/.bat） */
function execName(cmd) {
  if (!isWin) return cmd;
  return cmd + '.exe';
}

/** 在 PATH 中查找命令（跨平台，支持 Windows 扩展名） */
function which(cmd) {
  const envPath = process.env.PATH || '';
  const pathDirs = envPath.split(path.delimiter);

  const names = isWin
    ? [cmd + '.exe', cmd + '.cmd', cmd + '.bat', cmd]
    : [cmd];

  for (const dir of pathDirs) {
    for (const name of names) {
      const full = path.join(dir, name);
      if (fs.existsSync(full)) return full;
    }
  }
  return null;
}

function main() {
  const jar = findJar();
  if (!jar) {
    console.error('[ERROR] 未找到 jargraph JAR 包');
    console.error('[HINT] 请确保以下之一可用：');
    console.error(`  1) 当前目录存在 target/${JAR_NAME}`);
    console.error(`  2) npm 包内置了 jar（npm run build）`);
    console.error(`  3) 手动放置到 ${USER_JAR}`);
    process.exit(1);
  }

  const java = findJava();
  if (!java) {
    console.error('[ERROR] 未找到 Java 运行时');
    console.error('[HINT] 解决方法（任选其一）：');
    console.error('  1) 安装 JDK 并配置 JAVA_HOME');
    console.error('  2) 将 java 加入系统 PATH');
    console.error('  3) 使用 SDKMAN / Homebrew / Chocolatey 安装 Java');
    process.exit(1);
  }

  // 探测 mvn 并注入环境变量（供 Kotlin 进程读取）
  const mvn = findMvn();
  const env = { ...process.env };
  if (mvn) {
    env.JARGRAPH_MVN = mvn;
  }

  const javaArgs = ['-jar', jar, ...process.argv.slice(2)];
  const child = spawn(java, javaArgs, {
    stdio: 'inherit',
    cwd: process.cwd(),
    env,
  });

  child.on('exit', (code) => {
    process.exitCode = code ?? 0;
  });
}

main();
