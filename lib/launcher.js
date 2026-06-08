#!/usr/bin/env node
/**
 * jargraph launcher
 *
 * 1. 优先使用当前项目 target/ 下的 jar（开发模式）
 * 2. 其次使用 npm 包内置的 jar（发布模式）
 * 3. 最后尝试 ~/.jargraph/jargraph.jar（用户手动放置）
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const JAR_NAME = 'jar-graph-0.1.0-SNAPSHOT.jar';
const USER_JAR = path.join(require('os').homedir(), '.jargraph', 'jargraph.jar');

function findJar() {
  // 1. 当前目录 target/ 下的 jar（开发模式）
  const localTarget = path.resolve(process.cwd(), 'target', JAR_NAME);
  if (fs.existsSync(localTarget)) {
    return localTarget;
  }

  // 2. npm 包内置的 jar（发布模式：与 launcher.js 同级目录的 dist/ 或 ../target/）
  const bundled = path.resolve(__dirname, '..', 'target', JAR_NAME);
  if (fs.existsSync(bundled)) {
    return bundled;
  }
  const distBundled = path.resolve(__dirname, '..', 'dist', 'jargraph.jar');
  if (fs.existsSync(distBundled)) {
    return distBundled;
  }

  // 3. 用户家目录手动放置的 jar
  if (fs.existsSync(USER_JAR)) {
    return USER_JAR;
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

  const javaArgs = ['-jar', jar, ...process.argv.slice(2)];
  const child = spawn('java', javaArgs, {
    stdio: 'inherit',
    cwd: process.cwd(),
  });

  child.on('exit', (code) => {
    process.exitCode = code ?? 0;
  });
}

main();
