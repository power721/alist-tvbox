<template>
  <div class="vod">

    <el-row justify="space-between">
      <el-col :span="18">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="(item,index) in paths">
            <a id="copy" @click="copy(item.path)" v-if="index==paths.length-1">
              {{ item.text }}
            </a>
            <a @click="loadFolder(item.path)" v-else>
              {{ item.text }}
            </a>
          </el-breadcrumb-item>
        </el-breadcrumb>
      </el-col>

      <el-col :span="2">
        <el-input v-model="keyword" @keyup.enter="search" :disabled="searching" clearable placeholder="搜索电报资源">
          <template #append>
            <el-button :icon="Search" :disabled="searching" @click="search"/>
          </template>
        </el-input>
      </el-col>

      <el-col :span="2">
        <el-button :icon="HomeFilled" circle @click="goBack" v-if="isHistory"/>
        <el-button :icon="Film" circle @click="goHistory" v-else/>
        <el-button :icon="Setting" circle @click="settingVisible=true" v-if="store.admin"/>
        <el-button :icon="Plus" circle @click="handleAdd"/>
        <el-button type="warning" @click="openDoubanMode">豆瓣</el-button>
      </el-col>
    </el-row>

    <div class="divider"></div>

    <el-row justify="center">
      <el-col :xs="3" :sm="3" :md="5" :span="9" v-if="results.length">
        {{ filteredResults.length }}/{{ results.length }}条搜索结果&nbsp;&nbsp;
        <el-select style="width: 90px" v-model="shareType" @change="filterSearchResults">
          <el-option
            v-for="item in options"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-button :icon="Delete" circle @click="clearSearch"></el-button>
        <el-table :data="filteredResults" v-loading="searching" class="results" @row-click="loadResult">
          <el-table-column prop="vod_name" label="内容">
            <template #default="scope">
              <el-tooltip :content="scope.row.vod_play_url">
                {{ getShareType(scope.row.type_name) }}
                {{ scope.row.vod_name }}
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-col>

      <el-col :xs="22" :sm="20" :md="18" :span="14">
        <el-row justify="end">
          <el-button type="danger" @click="handleDeleteBatch" v-if="isHistory&&selected.length">删除</el-button>
          <el-button type="danger" @click="handleCleanAll" v-if="isHistory">清空</el-button>
          <el-button @click="showScan" v-if="store.admin">同步影视</el-button>
          <el-button type="primary" :disabled="loading" @click="refresh">刷新</el-button>
        </el-row>
        <el-table v-loading="loading" :data="files" @selection-change="handleSelectionChange" style="width: 100%"
                  @row-click="load">
          <el-table-column type="selection" width="55" v-if="isHistory"/>
          <el-table-column prop="vod_name" label="名称" sortable>
            <template #default="scope">
              <el-popover :width="300" placement="left-start" v-if="scope.row.vod_pic">
                <template #reference>
                  📺
                </template>
                <template #default>
                  <el-image :src="imageUrl(scope.row.vod_pic)" loading="lazy" show-progress fit="cover"/>
                </template>
              </el-popover>
              <span v-else-if="scope.row.type==1">📂</span>
              <span v-else-if="scope.row.type==2">🎬</span>
              <span v-else-if="scope.row.type==3">🎧</span>
              <span v-else-if="scope.row.type==4">🖹</span>
              <span v-else-if="scope.row.type==5">📷</span>
              <span v-else-if="scope.row.type==9">▶️</span>
              <span>{{ scope.row.vod_name }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="vod_remarks" label="大小" width="120"
                           sortable :sort-method="sortFileSizes" v-if="!isHistory">
            <template #default="scope">
              {{ scope.row.vod_tag === 'file' ? scope.row.vod_remarks : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="dbid" label="豆瓣ID" width="120" v-if="!isHistory">
            <template #default="scope">
              <a @click.stop :href="'https://movie.douban.com/subject/'+scope.row.dbid" target="_blank"
                 v-if="scope.row.dbid">
                {{ scope.row.dbid }}
              </a>
            </template>
          </el-table-column>
          <el-table-column prop="vod_remarks" label="评分" width="90" sortable v-if="!isHistory">
            <template #default="scope">
              {{ scope.row.vod_tag === 'folder' ? scope.row.vod_remarks : '' }}
            </template>
          </el-table-column>
          <el-table-column prop="index" label="集数" width="90" v-if="isHistory">
            <template #default="scope">
              {{ scope.row.index > 0 ? scope.row.index : '' }}
            </template>
          </el-table-column>
          <el-table-column prop="vod_remarks" label="当前播放" width="250" v-if="isHistory"/>
          <el-table-column prop="progress" label="进度" width="120" v-if="isHistory"/>
          <el-table-column prop="vod_time" :label="isHistory?'播放时间':'修改时间'" width="165" sortable/>
          <el-table-column width="90" v-if="isHistory">
            <template #default="scope">
              <el-button link type="danger" @click.stop="showDelete(scope.row)">删除</el-button>
            </template>
          </el-table-column>
          <el-table-column width="120" v-else>
            <template #default="scope">
              <el-button link type="primary" @click.stop="showRenameFile(scope.row)" v-if="store.admin&&scope.row.type!=9">
                重命名
              </el-button>
              <el-button link type="danger" @click.stop="showRemoveFile(scope.row)" v-if="store.admin&&scope.row.type!=9">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next, jumper, sizes"
                       :current-page="page" :page-size="size" :total="total"
                       @current-change="handlePageChange" @size-change="handleSizeChange"/>
      </el-col>
    </el-row>

    <el-dialog v-model="imageVisible" :title="playItem.title" :fullscreen="true">
      <el-row>
        <el-col :span="18">
          <el-image style="height:1080px;width:100%" fit="contain" :src="playItem.url"
                    :preview-src-list="[playItem.url]" :hide-on-click-modal="true"/>
        </el-col>
        <el-col :span="5">
          <el-scrollbar ref="scrollbarRef" height="1050px">
            <div style="margin-left: 30px; margin-bottom: 10px;">
              {{ currentImageIndex + 1 }} / {{ images.length }}
            </div>
            <ul>
              <li v-for="(image, index) in images" :key="index" @click="loadDetail(image.vod_id)">
                <el-link type="primary" v-if="currentImageIndex==index">{{ image.vod_name }}</el-link>
                <el-link v-else>{{ image.vod_name }}</el-link>
              </li>
            </ul>
          </el-scrollbar>
        </el-col>
      </el-row>
    </el-dialog>

    <el-dialog class="player" v-model="dialogVisible" :fullscreen="true" :show-close="false" @opened="start"
               @close="stop">
      <template #header="{ close, titleId, titleClass }">
        <div class="my-header">
          <h5 :id="titleId" :class="titleClass">{{ playItem.title }}</h5>
          <div class="buttons">
            <el-button @click="toggleFullscreen">
              <el-icon class="el-icon--left">
                <FullScreen/>
              </el-icon>
              全屏
            </el-button>
            <el-button @click="close">
              <el-icon class="el-icon--left">
                <CircleCloseFilled/>
              </el-icon>
              关闭
            </el-button>
          </div>
        </div>
      </template>
      <div class="video-container">
        <el-row>
          <el-col :class="{wide:isWideMode}" :span="isWideMode ? 24 : 18">
            <video
              ref="videoPlayer"
              :src="playItem.url"
              :poster="poster"
              :autoplay="true"
              @ended="playNextVideo"
              @play="updatePlayState"
              @pause="updatePlayState"
              @volumechange="updateMuteState"
              controls>
            </video>
          </el-col>
          <el-col :span="5" v-show="!isWideMode">
            <div v-if="playlist.length>1">
              <div style="margin-left: 30px; margin-bottom: 10px;">
                <el-link @click="openListInVLC(currentVideoIndex)">第{{
                    currentVideoIndex + 1
                  }}集
                </el-link>
                /
                <el-link @click="openListInVLC(0)">总共{{ playlist.length }}集</el-link>
                <el-select v-model="order" @change="sort" placeholder="排序" style="width: 110px; margin-left: 10px">
                  <el-option
                    v-for="item in sortOrders"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </div>
              <el-scrollbar ref="scrollbarRef" height="1050px">
                <ul>
                  <li v-for="(video, index) in playlist" :key="index">
                    <el-popover placement="right" v-if="store.admin">
                      <template #reference>
                        <div>
                          <el-link type="primary" v-if="currentVideoIndex==index">
                            {{ video.title }}
                          </el-link>
                          <el-link @click="playVideo(index)" v-else>
                            {{ video.title }}
                          </el-link>
                          <span v-if="video.rating"> [{{ video.rating }}]</span>
                        </div>
                      </template>
                      <template #default>
                        <el-button-group class="ml-4">
                          <el-button type="primary" :icon="Edit" @click="showRename(video)"/>
                          <el-button type="danger" :icon="Delete" @click="showRemove(video)" v-if="video!=playItem"/>
                          <el-rate v-model="video.rating" @change="updateRating(video)" clearable/>
                        </el-button-group>
                      </template>
                    </el-popover>
                    <div v-else>
                      <el-link type="primary" v-if="currentVideoIndex==index">
                        {{ video.title }}
                      </el-link>
                      <el-link @click="playVideo(index)" v-else>
                        {{ video.title }}
                      </el-link>
                      <span v-if="video.rating"> [{{ video.rating }}]</span>
                    </div>
                  </li>
                </ul>
              </el-scrollbar>
            </div>
          </el-col>
        </el-row>

        <el-row>
          <el-col :class="{wide:isWideMode}" :span="isWideMode ? 24 : 18">
            <div>
              <el-button-group>
                <el-button @click="play" v-if="!playing">播放</el-button>
                <el-button @click="pause" v-if="playing">暂停</el-button>
                <el-button @click="replay">重播</el-button>
                <el-button @click="close">退出</el-button>
                <el-button @click="toggleMute">{{ isMuted ? '取消静音' : '静音' }}</el-button>
                <el-button @click="toggleWideMode">宽屏</el-button>
                <el-button @click="toggleFullscreen">全屏</el-button>
                <el-button @click="backward">后退</el-button>
                <el-button @click="forward">前进</el-button>
                <el-button @click="playPrevVideo" v-if="playlist.length>1">上集</el-button>
                <el-button @click="playNextVideo" v-if="playlist.length>1">下集</el-button>
                <el-popover placement="top" :width="400" trigger="click" v-if="playlist.length>1">
                  <template #reference>
                    <el-button @click="scrollEpisodeList">选集 {{ currentVideoIndex + 1 }}/{{ playlist.length }}</el-button>
                  </template>
                  <template #default>
                    <div style="max-height: 400px; overflow-y: auto;">
                      <div style="margin-bottom: 10px;">
                        <span style="margin-right: 10px;">排序:</span>
                        <el-select v-model="order" @change="sort" placeholder="排序" style="width: 130px;">
                          <el-option
                            v-for="item in sortOrders"
                            :key="item.value"
                            :label="item.label"
                            :value="item.value"
                          />
                        </el-select>
                      </div>
                      <el-scrollbar ref="episodeScrollbarRef" height="300px">
                        <div style="max-height: 300px; overflow-y: auto;">
                          <div
                            v-for="(video, index) in playlist"
                            :key="index"
                            @click="playVideo(index)"
                            style="padding: 8px; cursor: pointer; border-radius: 4px;"
                            :style="{
                              backgroundColor: currentVideoIndex === index ? '#409eff' : 'transparent',
                              color: currentVideoIndex === index ? '#fff' : 'inherit'
                            }"
                          >
                            {{ video.title }}
                          </div>
                        </div>
                      </el-scrollbar>
                    </div>
                  </template>
                </el-popover>
                <el-popover placement="bottom" width="400px" v-if="playlist.length>1">
                  <template #reference>
                    <el-button>片头<span v-if="skipStart">★</span></el-button>
                  </template>
                  <template #default>
                    跳过片头
                    <el-input-number
                      v-model="minute1"
                      class="mx-4"
                      :min="0"
                      :max="4"
                      value-on-clear="min"
                      controls-position="right"
                      @change="handleMinute1Change"
                    />
                    :
                    <el-input-number
                      v-model="second1"
                      class="mx-4"
                      :min="0"
                      :max="59"
                      :step="5"
                      value-on-clear="min"
                      controls-position="right"
                      @change="handleSecond1Change"
                    />
                  </template>
                </el-popover>
                <el-popover placement="bottom" width="400px" v-if="playlist.length>1">
                  <template #reference>
                    <el-button>片尾<span v-if="skipEnd">★</span></el-button>
                  </template>
                  <template #default>
                    跳过片尾
                    <el-input-number
                      v-model="minute2"
                      class="mx-4"
                      :min="0"
                      :max="4"
                      value-on-clear="min"
                      controls-position="right"
                      @change="handleMinute2Change"
                    />
                    :
                    <el-input-number
                      v-model="second2"
                      class="mx-4"
                      :min="0"
                      :max="59"
                      :step="5"
                      value-on-clear="min"
                      controls-position="right"
                      @change="handleSecond2Change"
                    />
                  </template>
                </el-popover>
                <el-popover placement="bottom" width="300px">
                  <template #reference>
                    <el-button>{{ currentSpeed == 1 ? '1.0' : currentSpeed == 2 ? '2.0' : currentSpeed }}</el-button>
                  </template>
                  <template #default>
                    播放速度
                    <el-slider v-model="currentSpeed" @change="setSpeed" :min="0.5" :max="2" :step="0.1" show-stops/>
                  </template>
                </el-popover>
                <el-popover placement="bottom" width="300px">
                  <template #reference>
                    <el-button>{{ currentVolume }}</el-button>
                  </template>
                  <template #default>
                    音量
                    <el-slider v-model="currentVolume" @change="setVolume" :min="0" :max="100" :step="5"/>
                  </template>
                </el-popover>
                <el-button @click="showScrape" title="刮削">
                  <el-icon>
                    <Connection/>
                  </el-icon>
                </el-button>
                <!--                <el-popover placement="bottom" width="100px" v-if="playItem.rating">-->
                <!--                  <template #reference>-->
                <!--                    <el-button :icon="StarFilled"></el-button>-->
                <!--                  </template>-->
                <!--                  <template #default>-->
                <!--                    <el-rate v-model="playItem.rating" @change="updateRating(playItem)" clearable/>-->
                <!--                  </template>-->
                <!--                </el-popover>-->
                <!--                <el-popover placement="bottom" width="100px">-->
                <!--                  <template #reference>-->
                <!--                    <el-button :icon="Star"></el-button>-->
                <!--                  </template>-->
                <!--                  <template #default>-->
                <!--                    <el-rate v-model="playItem.rating" @change="updateRating(playItem)" clearable/>-->
                <!--                  </template>-->
                <!--                </el-popover>-->
                <el-button @click="showPush" title="推送" v-if="devices.length">
                  <el-icon>
                    <Upload/>
                  </el-icon>
                </el-button>
                <el-popover placement="bottom" width="350px">
                  <template #reference>
                    <el-button :icon="Menu"></el-button>
                  </template>
                  <template #default>
                    <div class="players">
                      <a :href="'iina://weblink?url='+playItem.url"><img alt="iina" src="/iina.webp"></a>
                      <a :href="'potplayer://'+playItem.url"><img alt="potplayer" src="/potplayer.webp"></a>
                      <a :href="'vlc://'+playItem.url"><img alt="vlc" src="/vlc.webp"></a>
                      <a :href="'nplayer-'+playItem.url"><img alt="nplayer" src="/nplayer.webp"></a>
                      <a :href="'omniplayer://weblink?url='+playItem.url"><img alt="omniplayer" src="/omniplayer.webp"></a>
                      <a :href="'figplayer://weblink?url='+playItem.url"><img alt="figplayer" src="/figplayer.webp"></a>
                      <a :href="'infuse://x-callback-url/play?url='+playItem.url"><img alt="infuse" src="/infuse.webp"></a>
                      <a :href="'filebox://play?url='+playItem.url"><img alt="fileball" src="/fileball.webp"></a>
                      <!--<a :href="'iplay://play/any?type=url&url='+video.url"><img alt="iPlay" src="/iPlay.webp"></a>-->
                    </div>
                  </template>
                </el-popover>
                <el-popover placement="right-start">
                  <template #reference>
                    <el-button :icon="QuestionFilled"/>
                  </template>
                  <template #default>
                    <div>
                      <div>播放： 空格键</div>
                      <div>全屏： 回车键</div>
                      <div>退出： Esc</div>
                      <div>静音： m</div>
                      <div>减速： -</div>
                      <div>加速： +</div>
                      <div>原速： =</div>
                      <div>音量-： ↓</div>
                      <div>音量+： ↑</div>
                      <div>后退15秒： ←</div>
                      <div>前进15秒： →</div>
                      <div v-if="playlist.length>1">上集： PageUp</div>
                      <div v-if="playlist.length>1">下集： PageDown</div>
                    </div>
                  </template>
                </el-popover>
              </el-button-group>
            </div>
          </el-col>
        </el-row>

        <div class="divider"></div>

        <el-row>
          <el-col :class="{wide:isWideMode}" :span="18">
            <el-descriptions class="movie">
              <el-descriptions-item label="名称">{{ movies[0].vod_name }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ movies[0].type_name || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="年代">{{ movies[0].vod_year || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="地区">{{ movies[0].vod_area || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="语言">{{ movies[0].vod_lang || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="评分">{{ movies[0].vod_remarks || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="导演">{{ movies[0].vod_director || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="演员">{{ movies[0].vod_actor || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="豆瓣" v-if="movies[0].dbid">
                <a :href="'https://movie.douban.com/subject/'+movies[0].dbid" target="_blank">
                  {{ movies[0].dbid }}
                </a>
              </el-descriptions-item>
            </el-descriptions>
            <p>
              {{ movies[0].vod_content }}
            </p>
          </el-col>
        </el-row>
      </div>
    </el-dialog>

    <el-dialog v-model="formVisible" title="添加分享" @opened="focus">
      <el-form label-width="140" :model="form">
        <el-form-item label="分享链接" required>
          <el-input id="link" v-model="form.link" @keyup.enter="addShare" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="挂载路径">
          <el-input v-model="form.path" autocomplete="off" placeholder="留空为临时挂载"/>
        </el-form-item>
        <el-form-item label="提取码">
          <el-input v-model="form.code" autocomplete="off"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="formVisible=false">取消</el-button>
        <el-button type="primary" @click="addShare">添加</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="settingVisible" title="播放配置" fullscreen>
      <PlayConfig/>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="settingVisible=false">关闭</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="addVisible" title="刮削电影数据" width="60%">
      <el-form label-width="140px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="meta.type" class="ml-4">
            <el-radio label="movie" size="large">电影</el-radio>
            <el-radio label="tv" size="large">剧集</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="meta.name" id="meta-name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="年代">
          <el-input-number v-model="meta.year" :min="1900" :max="2099" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="路径">
          <el-input v-model="meta.path" autocomplete="off"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="addVisible=false">取消</el-button>
        <el-button type="primary" :disabled="!meta.path||!meta.name" @click="scrape">刮削</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="deleteVisible" title="删除播放记录" width="30%">
      <div v-if="batch">
        <p>是否删除选中的{{ selected.length }}个播放记录?</p>
      </div>
      <div v-else-if="clean">
        <p>是否清空全部播放记录?</p>
      </div>
      <div v-else>
        <p>是否删除播放记录 - {{ history.vod_name }}</p>
        <p>{{ history.path }}</p>
      </div>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteHistory">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="scanVisible" title="影视设备">
      <el-row>
        <el-col span="8">
          <div>影视扫码添加AList TvBox</div>
          <img alt="qr" :src="'data:image/png;base64,'+ base64QrCode" style="width: 200px;">
        </el-col>
        <el-col span="10">
          <el-input v-model="device.ip" style="width: 200px" placeholder="输入影视IP或者URL"
                    @keyup.enter="addDevice"></el-input>
          <el-button @click="addDevice">添加</el-button>
        </el-col>
        <el-col span="6">
          <el-button @click="scanDevices">扫描设备</el-button>
        </el-col>
      </el-row>

      <el-table :data="devices" border style="width: 100%">
        <el-table-column prop="name" label="名称" sortable width="180"/>
        <el-table-column prop="uuid" label="ID" sortable width="180"/>
        <el-table-column prop="ip" label="URL地址" sortable>
          <template #default="scope">
            <a :href="scope.row.ip" target="_blank">{{ scope.row.ip }}</a>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="200">
          <template #default="scope">
            <el-button link type="primary" size="small" @click="syncHistory(scope.row.id, 0)">同步</el-button>
            <el-button link type="primary" size="small" @click="syncHistory(scope.row.id, 1)">推送</el-button>
            <el-button link type="primary" size="small" @click="syncHistory(scope.row.id, 2)">拉取</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="confirm" title="删除影视设备" width="30%">
      <p>是否删除影视设备？</p>
      <p> {{ device.name }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="confirm = false">取消</el-button>
        <el-button type="danger" @click="deleteDevice">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="renameVisible" title="重命名文件" width="70%">
      <p>是否重命名文件？</p>
      <p> {{ editing.path }}</p>
      <el-input v-model="name"/>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" @click="rename">更新</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="removeVisible" title="删除文件" width="70%">
      <p>是否删除文件？</p>
      <p> {{ editing.path }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="removeVisible = false">取消</el-button>
        <el-button type="danger" @click="remove">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="pushVisible" title="推送播放地址" width="30%">
      <el-form label-width="auto">
        <el-form-item label="影视设备" required>
          <el-select
            v-model="device.id"
            style="width: 240px"
          >
            <el-option
              v-for="item in devices"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="pushVisible = false">取消</el-button>
        <el-button type="primary" @click="doPush">推送</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="doubanVisible" title="豆瓣电影" fullscreen>
      <el-container>
        <el-aside width="200px" v-loading="loadingCategories">
          <el-menu :default-active="selectedCategory" @select="handleCategorySelect">
            <el-menu-item v-for="cat in categories" :key="cat.type_id" :index="cat.type_id">
              {{ cat.type_name }}
            </el-menu-item>
          </el-menu>
        </el-aside>
        <el-main v-loading="loadingPosters">
          <el-row :gutter="30">
            <el-col :span="3" v-for="item in doubanItems" :key="item.vod_id" style="margin-bottom: 20px;">
              <el-card :body-style="{ padding: '0px', cursor: 'pointer' }" shadow="hover" @click="searchDoubanItem(item)">
                <el-image :src="item.vod_pic" fit="cover" style="width: 100%; height: 400px;" />
                <div style="padding: 10px;">
                  <div style="font-weight: bold; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                    {{ item.vod_name }}
                  </div>
                  <div style="font-size: 12px; color: #999; margin-top: 5px;">
                    {{ item.vod_remarks }}
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
          <el-pagination
            v-if="doubanTotal > 0"
            layout="total, prev, pager, next"
            :current-page="doubanPage"
            :page-size="35"
            :total="doubanTotal"
            @current-change="handleDoubanPageChange"
            style="margin-top: 20px; text-align: center;"
          />
        </el-main>
      </el-container>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
// @ts-nocheck
import {onMounted, onUnmounted, reactive, ref, watch} from 'vue'
import axios from "axios"
import {ElMessage, type ScrollbarInstance} from "element-plus";
import type {VodItem} from "@/model/VodItem";
import type {PlayItem} from "@/model/PlayItem";
import {useRoute, useRouter} from "vue-router";
import clipBorad from "vue-clipboard3";
import {debounce} from 'lodash-es'
import {
  CircleCloseFilled,
  Connection,
  Delete,
  Edit,
  Film,
  FullScreen,
  HomeFilled,
  Menu,
  Plus,
  QuestionFilled,
  Search,
  Setting,
  Upload,
} from "@element-plus/icons-vue";
import type {Device} from "@/model/Device";
import PlayConfig from "@/components/PlayConfig.vue";
import {store} from "@/services/store";

let {toClipboard} = clipBorad();

let timer = 0
const route = useRoute()
const router = useRouter()
const videoPlayer = ref(null)
const scrollbarRef = ref<ScrollbarInstance>()
const episodeScrollbarRef = ref<ScrollbarInstance>()
const filePath = ref('/')
const keyword = ref('')
const order = ref('index')
const shareType = ref('ALL')
const name = ref('')
const poster = ref('')
const cover = ref('')
const base64QrCode = ref('')
const devices = ref<Device[]>([])
const movies = ref<VodItem[]>([])
const playFrom = ref<string[]>([])
const playlist = ref<PlayItem[]>([])
const playItem = ref<PlayItem>({})
const editing = ref<PlayItem>({})
const currentVideoIndex = ref(0)
const currentImageIndex = ref(0)
const duration = ref(0)
const currentTime = ref(0)
const currentSpeed = ref(1)
const currentVolume = ref(100)
const skipStart = ref(0)
const skipEnd = ref(0)
const minute1 = ref(0)
const second1 = ref(0)
const minute2 = ref(0)
const second2 = ref(0)
const loading = ref(false)
const playing = ref(false)
const isMuted = ref(false)
const isFullscreen = ref(false)
const isWideMode = ref(false)
const dialogVisible = ref(false)
const imageVisible = ref(false)
const formVisible = ref(false)
const scanVisible = ref(false)
const confirm = ref(false)
const needRefresh = ref(false)
const renameVisible = ref(false)
const removeVisible = ref(false)
const pushVisible = ref(false)
const doubanVisible = ref(false)
const categories = ref<any[]>([])
const doubanItems = ref<any[]>([])
const doubanPage = ref(1)
const doubanTotal = ref(0)
const loadingCategories = ref(false)
const loadingPosters = ref(false)
const selectedCategory = ref('')
const batch = ref(false)
const clean = ref(false)
const deleteVisible = ref(false)
const settingVisible = ref(false)
const addVisible = ref(false)
const isHistory = ref(false)
const searching = ref(false)
const page = ref(parseInt(route.query.page) || 1)
const size = ref(parseInt(route.query.size) || 50)
const total = ref(0)
const files = ref<VodItem[]>([])
const images = ref<VodItem[]>([])
const results = ref<VodItem[]>([])
const filteredResults = ref<VodItem[]>([])
const paths = ref<Item[]>([])
const selected = ref<Item[]>([])
const device = ref<Device>({
  name: "",
  type: "",
  uuid: "",
  id: 0,
  ip: ''
})
const pageInfo = reactive({})
const history = ref({
  id: 0,
  vod_name: ''
})
const form = ref({
  link: '',
  path: '',
  code: '',
})
const meta = ref({
  type: 'tv',
  name: '',
  year: null,
  path: '',
})
const options = [
  {label: '全部', value: 'ALL'},
  {label: '百度', value: '10'},
  {label: '天翼', value: '9'},
  {label: '夸克', value: '5'},
  {label: 'UC', value: '7'},
  {label: '阿里', value: '0'},
  {label: '115', value: '8'},
  {label: '123', value: '3'},
  {label: '迅雷', value: '2'},
  {label: '移动', value: '6'},
  {label: 'PikPak', value: '1'},
]
const sortOrders = [
  {
    value: 'index',
    label: '原始顺序',
  },
  // {
  //   value: 'id,asc',
  //   label: 'ID升序',
  // },
  // {
  //   value: 'id,desc',
  //   label: 'ID降序',
  // },
  {
    value: 'name,asc',
    label: '名称升序',
  },
  {
    value: 'name,desc',
    label: '名称降序',
  },
  {
    value: 'rating,asc',
    label: '评分升序',
  },
  {
    value: 'rating,desc',
    label: '评分降序',
  },
  {
    value: 'time,asc',
    label: '时间升序',
  },
  {
    value: 'time,desc',
    label: '时间降序',
  },
  {
    value: 'size,asc',
    label: '大小升序',
  },
  {
    value: 'size,desc',
    label: '大小降序',
  },
]

const sortFileSizes = (a, b) => {
  const sizeA = parseFileSize(a.vod_remarks);
  const sizeB = parseFileSize(b.vod_remarks);
  return sizeA - sizeB;
};

const parseFileSize = (sizeStr) => {
  const units = {
    B: 1,
    KB: 1024,
    MB: 1024 * 1024,
    GB: 1024 * 1024 * 1024,
    TB: 1024 * 1024 * 1024 * 1024,
  };

  const match = sizeStr.match(/^(\d+\.?\d*)\s*([A-Za-z]+)/);
  if (!match) return 0;

  const value = parseFloat(match[1]);
  const unit = match[2].toUpperCase();

  const unitKey = Object.keys(units).find(
    key => key.toUpperCase() === unit
  );

  return value * (units[unitKey] || 1);
};

const showScan = () => {
  axios.get('/api/qr-code').then(({data}) => {
    base64QrCode.value = data
    scanVisible.value = true
  })
  loadDevices()
}

const loadDevices = () => {
  axios.get('/api/devices').then(({data}) => {
    devices.value = data
  })
}

const syncHistory = (id: number, mode: number) => {
  axios.post(`/devices/${store.token}/${id}/sync?mode=${mode}`).then(() => {
    ElMessage.success('同步成功')
    if (isHistory.value) {
      loadHistory()
    }
  })
}

const scanDevices = () => {
  axios.post(`/api/devices/-/scan`).then(({data}) => {
    ElMessage.success(`扫描完成，添加了${data}个设备`)
    loadDevices()
  })
}

const handleDelete = (data: Device) => {
  device.value = data
  confirm.value = true
}

const addDevice = () => {
  if (!device.value.ip) {
    return
  }
  axios.post(`/api/devices?ip=` + device.value.ip).then(() => {
    confirm.value = false
    device.value.ip = ''
    ElMessage.success('添加成功')
    axios.get('/api/devices').then(({data}) => {
      devices.value = data
    })
  })
}

const deleteDevice = () => {
  axios.delete(`/api/devices/${device.value.id}`).then(() => {
    confirm.value = false
    ElMessage.success('删除成功')
    axios.get('/api/devices').then(({data}) => {
      devices.value = data
    })
  })
}

const handleAdd = () => {
  form.value = {
    link: '',
    path: '',
    code: '',
  }
  formVisible.value = true
}

const search = () => {
  searching.value = true
  return axios.get('/api/telegram/search?wd=' + keyword.value).then(({data}) => {
    searching.value = false
    results.value = data.map(e => {
      return {
        vod_id: e.id + '',
        vod_name: e.name,
        vod_tag: 'folder',
        vod_time: formatDate(e.time),
        type_name: e.type,
        vod_play_from: e.channel,
        vod_play_url: e.link,
      }
    })
    if (results.value.length == 0) {
      ElMessage.info('无搜索结果')
    }
    filterSearchResults()
  }, () => {
    searching.value = false
  })
}

const filterSearchResults = () => {
  filteredResults.value = shareType.value != 'ALL' ? results.value.filter(e => e.type_name == shareType.value) : results.value
}

const getShareType = (type: string) => {
  if (type == '0') {
    return '📀'
  }
  if (type == '5') {
    return '🚀'
  }
  if (type == '7') {
    return '🌞'
  }
  if (type == '3') {
    return '💾'
  }
  if (type == '8') {
    return '📡'
  }
  if (type == '9') {
    return '☁'
  }
  if (type == '6') {
    return '🚁'
  }
  if (type == '1') {
    return '🅿'
  }
  if (type == '2') {
    return '⚡'
  }
  if (type == '10') {
    return '🐌'
  }
  return ''
}

const clearSearch = () => {
  keyword.value = ''
  results.value = []
  filteredResults.value = []
}

const handleSelectionChange = (val: ShareInfo[]) => {
  selected.value = val
}

const updateRating = (item: PlayItem) => {
  const id = item.id;
  axios.post(`/api/videos/${id}/rate`, {rating: item.rating}).then(() => {
    ElMessage.success('更新成功')
  })
}

const showRenameFile = (video: VodItem) => {
  editing.value.title = video.vod_name
  editing.value.path = video.path
  editing.value.id = video.vod_id.split('$')[1]
  name.value = video.vod_name
  renameVisible.value = true
  needRefresh.value = true
}

const showRename = (video: PlayItem) => {
  editing.value = video
  name.value = video.name
  renameVisible.value = true
}

const rename = () => {
  const id = editing.value.id;
  axios.post(`/api/videos/${id}/rename`, {name: name.value}).then(() => {
    renameVisible.value = false
    const index = editing.value.title.lastIndexOf('(')
    if (index > -1) {
      const size = editing.value.title.substring(index)
      editing.value.title = name.value + size
    }
    editing.value.name = name.value
    ElMessage.success('重命名成功')
    if (needRefresh.value) {
      refresh()
    }
  })
}

const showRemoveFile = (video: VodItem) => {
  editing.value.path = video.path
  editing.value.id = video.vod_id.split('$')[1]
  removeVisible.value = true
  needRefresh.value = true
}

const showRemove = (video: PlayItem) => {
  editing.value = video
  removeVisible.value = true
}

const remove = () => {
  const id = editing.value.id;
  axios.delete(`/api/videos/${id}`).then(() => {
    removeVisible.value = false
    playlist.value = playlist.value.filter(e => e != editing.value)
    currentVideoIndex.value = playlist.value.findIndex(e => e === playItem.value)
    ElMessage.success('删除成功')
    if (needRefresh.value) {
      refresh()
    }
  })
}

const showScrape = () => {
  const movie = movies.value[0]
  meta.value.path = movie.type == 9 ? getParent(movie.path) : movie.path
  meta.value.name = movie.vod_name
  meta.value.year = movie.vod_year
  addVisible.value = true
  setTimeout(() => document.getElementById('meta-name').focus(), 500)
}

const scrape = () => {
  axios.post('/api/tmdb/meta/-/scrape', meta.value).then(({data}) => {
    if (data) {
      const movie = movies.value[0]
      movie.vod_name = data.vod_name
      movie.vod_lang = data.vod_lang
      movie.vod_actor = data.vod_actor
      movie.vod_director = data.vod_director
      movie.vod_area = data.vod_area
      movie.vod_content = data.vod_content
      movie.vod_year = data.vod_year
      movie.vod_remarks = data.vod_remarks
      movie.type_name = data.type_name
      ElMessage.success('刮削成功')
      addVisible.value = false
    } else {
      ElMessage.warning('刮削失败')
    }
  })
}

const focus = () => {
  document.getElementById('link').focus()
}

const addShare = () => {
  axios.post('/api/share-link', form.value).then(({data}) => {
    loadFolder(data)
    formVisible.value = false
  })
}

const loadResult = (row: any) => {
  form.value = {
    link: row.vod_play_url,
    path: '',
    code: '',
  }
  toClipboard(row.vod_play_url).then()
  axios.post('/api/share-link', form.value).then(({data}) => {
    loadFolder(data)
  })
}

const loadShare = (link: string) => {
  form.value = {
    link: link,
    path: '',
    code: '',
  }
  axios.post('/api/share-link', form.value).then(({data}) => {
    loadFolder(data)
  })
}

const load = (row: any) => {
  if (row.type == 1) {
    loadFolder(row.path)
  } else {
    loadDetail(row.vod_id)
  }
}

const goHistory = () => {
  router.push('/vod/~history')
}

const goBack = () => {
  router.back()
}

const goParent = (path: string) => {
  path = getPath(path)
  const index = path.lastIndexOf('/')
  const parent = path.substring(0, index)
  loadFolder(parent)
}

const imageUrl = (url: string) => {
  if (url.endsWith("/folder.png")) {
    return url;
  }
  if (url.endsWith("/list.png")) {
    return url;
  }
  return '/images?url=' + encodeURIComponent(url)
}

const handlePageChange = (value: number) => {
  page.value = value
  if (!pageInfo[filePath.value]) pageInfo[filePath.value] = {page: value, pageSize: size.value}
  pageInfo[filePath.value].page = value
}

const handleSizeChange = (value: number) => {
  size.value = value
  if (!pageInfo[filePath.value]) pageInfo[filePath.value] = {page: page.value, pageSize: value}
  pageInfo[filePath.value].size = value
}

const reload = (value: number) => {
  page.value = value
  if (isHistory.value) {
    loadHistory()
  } else {
    loadFiles(paths.value[paths.value.length - 1].path)
  }
}

const copy = (text: string) => {
  toClipboard(text).then(() => {
    ElMessage.success('路径已复制')
  })
}

const loadFolder = (path: string) => {
  if (path == '/~history') {
    return
  }
  router.push('/vod' + getPath(path).replace('\t', '%09') + '?page=1&size=' + size.value)
  filePath.value = path
}

const fetchData = () => {
  loadFiles(filePath.value)
}

const debouncedFetch = debounce(fetchData, 50)

const loadFiles = (path: string) => {
  if (path == '/~history') {
    loadHistory()
    return
  }
  const id = extractPaths(path)
  isHistory.value = false
  loading.value = true
  files.value = []
  axios.get('/vod/' + store.token + '?ac=web&pg=' + page.value + '&size=' + size.value + '&t=' + id).then(({data}) => {
    files.value = data.list
    images.value = data.list.filter(e => e.type == 5)
    total.value = data.total
    loading.value = false
  }, () => {
    loading.value = false
  })
}

const getParent = (path: string) => {
  path = getPath(path)
  const index = path.lastIndexOf('/')
  return path.substring(0, index)
}

const getPath = (id: string) => {
  return decodeURIComponent(id).replace('1$', '').replace('$1', '')
}

const extractPaths = (id: string) => {
  const path = getPath(id)
  if (path == '/') {
    paths.value = [{text: '🏠首页', path: '/'}]
  } else {
    const array = path.split('/')
    const items: Item[] = []
    for (let index = 0; index < array.length; ++index) {
      const path = array.slice(0, index + 1).join('/')
      const text = array[index]
      items.push({text: text ? text : '🏠首页', path: path ? path : '/'})
    }
    paths.value = items
  }
  if (id.startsWith('1$')) {
    return id
  }
  return '1$' + encodeURIComponent(path) + '$1'
}

const loadDetail = (id: string) => {
  loading.value = true
  axios.get('/vod/' + store.token + '?ac=web&ids=' + id).then(({data}) => {
    if (isHistory.value) {
      goParent(data.list[0].path)
    }
    if (data.list[0].type == 5) {
      let img = data.list[0]
      currentImageIndex.value = images.value.findIndex(e => e.vod_id == id)
      playItem.value.url = img.vod_play_url
      playItem.value.title = img.vod_name
      loading.value = false
      imageVisible.value = true
      return
    }

    movies.value = data.list
    //movies.value[0].vod_id = id
    const pic = movies.value[0].vod_pic
    if (pic && pic.includes('doubanio.com')) {
      poster.value = '/images?url=' + pic.replace('s_ratio_poster', 'm')
    } else {
      poster.value = cover.value ? '/images?url=' + cover.value : ''
    }
    playlist.value = movies.value[0].items
    let index = 0
    for (const item of playlist.value) {
      item.index = index++
      if (!item.rating) {
        item.rating = 0
      }
      if (item.time) {
        item.time = new Date(item.time)
      } else {
        item.time = new Date()
      }
    }
    playFrom.value = movies.value[0].vod_play_from.split("$$$");
    getHistory(movies.value[0].vod_id).then(() => {
      getPlayUrl()
      loading.value = false
      dialogVisible.value = true
    }, () => {
      loading.value = false
    })
  }, () => {
    loading.value = false
  })
}

const handleKeyDown = (event: KeyboardEvent) => {
  if (formVisible.value || addVisible.value || renameVisible.value || removeVisible.value) {
    return;
  }
  if (document.activeElement === videoPlayer.value && event.code === 'Space') {
    return;
  }
  if (imageVisible.value) {
    if (event.code === 'ArrowRight') {
      event.preventDefault()
      showNextImage()
    } else if (event.code === 'ArrowLeft') {
      event.preventDefault()
      showPrevImage()
    }
    return;
  }
  if (!dialogVisible.value) {
    // if (event.code === 'Space' && files.value.length > 0 && files.value[0].vod_tag === 'file') {
    //   event.preventDefault()
    //   loadDetail(files.value[0].vod_id)
    // } else if (event.code === 'Escape' && paths.value.length > 1) {
    //   event.preventDefault()
    //   loadFolder(paths.value[paths.value.length - 2].path)
    // } else if (event.code === 'KeyA') {
    //   if (event.ctrlKey || event.metaKey) {
    //     return;
    //   }
    //   event.preventDefault()
    //   handleAdd()
    // } else if (event.code === 'KeyH') {
    //   if (event.ctrlKey || event.metaKey) {
    //     return;
    //   }
    //   event.preventDefault()
    //   loadHistory()
    // } else if (event.code === 'KeyS') {
    //   if (event.ctrlKey || event.metaKey) {
    //     return;
    //   }
    //   event.preventDefault()
    //   search()
    // }
    return
  }
  if (event.code === 'Space') {
    event.preventDefault()
    togglePlay()
  } else if (event.code === 'ArrowRight') {
    event.preventDefault()
    forward()
  } else if (event.code === 'ArrowLeft') {
    event.preventDefault()
    backward()
  } else if (event.code === 'PageUp') {
    event.preventDefault()
    playPrevVideo()
  } else if (event.code === 'PageDown') {
    event.preventDefault()
    playNextVideo()
  } else if (event.code === 'Enter') {
    event.preventDefault()
    toggleFullscreen()
  } else if (event.key === '+') {
    event.preventDefault()
    incSpeed()
  } else if (event.key === '-') {
    event.preventDefault()
    decSpeed()
  } else if (event.key === '=') {
    event.preventDefault()
    setSpeed(1.0)
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    decVolume()
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    incVolume()
  } else if (event.code === 'KeyM') {
    if (event.ctrlKey || event.metaKey) {
      return;
    }
    event.preventDefault()
    toggleMute()
  } else if (event.code === 'KeyC') {
    if (event.ctrlKey || event.metaKey) {
      return;
    }
    event.preventDefault()
    copyPlayUrl()
  } else if (event.code === 'KeyV') {
    if (event.ctrlKey || event.metaKey) {
      return;
    }
    event.preventDefault()
    openInVLC()
  }
}

const togglePlay = () => {
  if (videoPlayer.value) {
    if (playing.value) {
      pause()
    } else {
      play()
    }
  }
}

const start = () => {
  if (videoPlayer.value) {
    if (currentTime.value < skipStart.value) {
      currentTime.value = skipStart.value
    }
    videoPlayer.value.currentTime = currentTime.value
    videoPlayer.value.playbackRate = currentSpeed.value
    videoPlayer.value.volume = currentVolume.value / 100.
    videoPlayer.value.addEventListener('playbackratechange', handleSpeedChange)
    videoPlayer.value.addEventListener('volumechange', handleVolumeChange)
    videoPlayer.value.addEventListener('timeupdate', handleTimeUpdate)
    //videoPlayer.value.addEventListener('durationchange', handleDurationChange)
    videoPlayer.value.addEventListener('loadedmetadata', handleDurationChange)
    scroll()
    play()
  }
}

const close = () => {
  if (videoPlayer.value) {
    videoPlayer.value.removeEventListener('playbackratechange', handleSpeedChange)
    videoPlayer.value.removeEventListener('volumechange', handleVolumeChange)
    videoPlayer.value.removeEventListener('timeupdate', handleTimeUpdate)
    //videoPlayer.value.removeEventListener('durationchange', handleDurationChange)
  }
  dialogVisible.value = false
}

const stop = () => {
  document.title = 'AList - TvBox'
  pause()
}

const handleMinute1Change = (value: number) => {
  minute1.value = value || 0
  skipStart.value = minute1.value * 60 + second1.value
  saveHistory()
}

const handleSecond1Change = (value: number) => {
  second1.value = value || 0
  skipStart.value = minute1.value * 60 + second1.value
  saveHistory()
}

const handleMinute2Change = (value: number) => {
  minute2.value = value || 0
  skipEnd.value = minute2.value * 60 + second2.value
  saveHistory()
}

const handleSecond2Change = (value: number) => {
  second2.value = value || 0
  skipEnd.value = minute2.value * 60 + second2.value
  saveHistory()
}

const scroll = () => {
  if (scrollbarRef.value) {
    scrollbarRef.value.setScrollTop(currentVideoIndex.value * 21.5)
  }
}

const scrollEpisodeList = () => {
  setTimeout(() => {
    if (episodeScrollbarRef.value) {
      episodeScrollbarRef.value.setScrollTop(currentVideoIndex.value * 40)
    }
  }, 100)
}

const startPlay = () => {
  setTimeout(skip, 500)
}

const skip = () => {
  if (videoPlayer.value) {
    if (videoPlayer.value.currentTime < skipStart.value) {
      videoPlayer.value.currentTime = skipStart.value
    }
  }
}

const play = () => {
  if (videoPlayer.value) {
    const res = videoPlayer.value.play();
    if (res) {
      res.then(_ => {
        playing.value = true
        saveHistory()
      }).catch(e => {
        console.error(e)
      })
    }
  }
}

const pause = () => {
  if (videoPlayer.value) {
    const res = videoPlayer.value.pause();
    if (res) {
      res.then(_ => {
        playing.value = false
      }).catch(e => {
        console.error(e)
      })
    }
    saveHistory()
  }
}

const forward = () => {
  if (videoPlayer.value) {
    videoPlayer.value.currentTime += 15;
    play()
  }
}

const backward = () => {
  if (videoPlayer.value) {
    videoPlayer.value.currentTime -= 15;
    play()
  }
}

const toggleMute = () => {
  if (videoPlayer.value) {
    videoPlayer.value.muted = !videoPlayer.value.muted;
  }
}

const toggleFullscreen = () => {
  if (videoPlayer.value) {
    if (!isFullscreen.value) {
      enterFullscreen(videoPlayer.value);
    } else {
      exitFullscreen();
    }
  }
}

const toggleWideMode = () => {
  isWideMode.value = !isWideMode.value
  localStorage.setItem('wideMode', isWideMode.value.toString())
}

const openDoubanMode = () => {
  doubanVisible.value = true
  if (categories.value.length === 0) {
    loadingCategories.value = true
    axios.get('/tg-db/' + store.token).then(({data}) => {
      loadingCategories.value = false
      if (data.class) {
        categories.value = data.class
        if (categories.value.length > 0) {
          handleCategorySelect(categories.value[0].type_id)
        }
      }
    }).catch(() => {
      loadingCategories.value = false
      ElMessage.error('获取分类失败')
    })
  }
}

const handleCategorySelect = (typeId: string) => {
  selectedCategory.value = typeId
  doubanPage.value = 1
  loadDoubanItems(typeId, 1)
}

const loadDoubanItems = (typeId: string, page: number) => {
  loadingPosters.value = true
  axios.get('/tg-db/' + store.token + '?ac=web&size=35&t=' + encodeURIComponent(typeId) + '&pg=' + page).then(({data}) => {
    loadingPosters.value = false
    if (data.list) {
      doubanItems.value = data.list
      doubanTotal.value = data.total || data.pagecount * 20 || 0
    }
  }).catch(() => {
    loadingPosters.value = false
    ElMessage.error('获取列表失败')
  })
}

const handleDoubanPageChange = (page: number) => {
  doubanPage.value = page
  loadDoubanItems(selectedCategory.value, page)
}

const searchDoubanItem = (item: any) => {
  keyword.value = item.vod_name
  search().then(() => {
    doubanVisible.value = false
  })
}

const enterFullscreen = (element) => {
  if (element.requestFullscreen) {
    element.requestFullscreen();
  } else if (element.mozRequestFullScreen) { // Firefox
    element.mozRequestFullScreen();
  } else if (element.webkitRequestFullscreen) { // Chrome, Safari
    element.webkitRequestFullscreen();
  } else if (element.msRequestFullscreen) { // IE/Edge
    element.msRequestFullscreen();
  }
}

const exitFullscreen = () => {
  if (document.exitFullscreen) {
    document.exitFullscreen();
  } else if (document.mozCancelFullScreen) {
    document.mozCancelFullScreen();
  } else if (document.webkitExitFullscreen) {
    document.webkitExitFullscreen();
  } else if (document.msExitFullscreen) {
    document.msExitFullscreen();
  }
};

const handleFullscreenChange = () => {
  isFullscreen.value = document.fullscreenElement === videoPlayer.value;
}

const setVolume = (volume: number) => {
  currentVolume.value = volume
  localStorage.setItem('volume', volume)
  if (videoPlayer.value) {
    videoPlayer.value.volume = volume / 100.0
  }
}

const handleVolumeChange = () => {
  if (videoPlayer.value) {
    currentVolume.value = Math.round(videoPlayer.value.volume * 100)
    localStorage.setItem('volume', currentVolume.value)
  }
}

const incVolume = () => {
  let volume = currentVolume.value + 5
  if (volume > 100) {
    volume = 100
  }
  setVolume(volume)
}

const decVolume = () => {
  let volume = currentVolume.value - 5
  if (volume < 0) {
    volume = 0
  }
  setVolume(volume)
}

const handleTimeUpdate = () => {
  if (videoPlayer.value) {
    const time = videoPlayer.value.currentTime
    if (currentVideoIndex.value + 1 < playlist.value.length && duration.value > skipStart.value + skipEnd.value && time + skipEnd.value > duration.value && time < duration.value) {
      videoPlayer.value.currentTime = duration.value
    }
  }
}

const handleDurationChange = () => {
  if (videoPlayer.value) {
    duration.value = videoPlayer.value.duration
  }
}

const setSpeed = (speed: number) => {
  currentSpeed.value = speed
  if (videoPlayer.value) {
    videoPlayer.value.playbackRate = speed
  }
}

const incSpeed = () => {
  let speed = parseFloat((currentSpeed.value + 0.1).toFixed(1))
  if (speed > 2.0) {
    speed = 2.0
  }
  setSpeed(speed)
}

const decSpeed = () => {
  let speed = parseFloat((currentSpeed.value - 0.1).toFixed(1))
  if (speed < 0.5) {
    speed = 0.5
  }
  setSpeed(speed)
}

const handleSpeedChange = () => {
  if (videoPlayer.value) {
    currentSpeed.value = videoPlayer.value.playbackRate
  }
}

const updatePlayState = () => {
  if (videoPlayer.value) {
    playing.value = !videoPlayer.value.paused;
  }
}

const updateMuteState = () => {
  if (videoPlayer.value) {
    isMuted.value = videoPlayer.value.muted;
  }
}

const sort = () => {
  // 保存当前播放视频的URL作为标识
  const currentUrl = playItem.value.url

  switch (order.value) {
    case "index":
      playlist.value.sort((a, b) => a.index - b.index)
      break
    case "name,asc":
      playlist.value.sort((a, b) => a.name.localeCompare(b.name))
      break
    case "name,desc":
      playlist.value.sort((a, b) => b.name.localeCompare(a.name))
      break
    case "size,asc":
      playlist.value.sort((a, b) => a.size - b.size)
      break
    case "size,desc":
      playlist.value.sort((a, b) => b.size - a.size)
      break
    case "rating,asc":
      playlist.value.sort((a, b) => {
        if (a.rating === b.rating) {
          return a.index - b.index
        }
        return a.rating - b.rating
      })
      break
    case "rating,desc":
      playlist.value.sort((a, b) => {
        if (a.rating === b.rating) {
          return a.index - b.index
        }
        return b.rating - a.rating
      })
      break
    case "time,asc":
      playlist.value.sort((a, b) => {
        if (a.time === b.time) {
          return a.index - b.index
        }
        return a.time - b.time
      })
      break
    case "time,desc":
      playlist.value.sort((a, b) => {
        if (a.time === b.time) {
          return a.index - b.index
        }
        return b.time - a.time
      })
      break
  }

  // 根据URL重新找到当前视频的索引
  currentVideoIndex.value = playlist.value.findIndex(e => e.url === currentUrl)
}

const getPlayUrl = () => {
  const index = currentVideoIndex.value
  playItem.value = Object.assign({}, playlist.value[index])
  document.title = playItem.value.title
  saveHistory()
}

const copyPlayUrl = () => {
  toClipboard(playItem.value.url).then(() => {
    ElMessage.success('播放地址复制成功')
  })
}

const openListInVLC = (start: number) => {
  const url = buildM3u8Url(start)
  openUrlInVLC(url)
}

const buildM3u8Url = (start: number) => {
  const movie = movies.value[0]
  const id = movie.vod_id
  let url = playItem.value.url
  if (movie.type === 9) {
    url = window.location.origin + '/m3u8/' + store.token + '?id=' + id + '$' + start
  }
  return url
}

const openInVLC = () => {
  const url = playItem.value.url + '?name=' + encodeURIComponent(playItem.value.title)
  openUrlInVLC(url)
}

const openUrlInVLC = (url: string) => {
  const vlcAttempt = window.open(`vlc://${url}`, '_blank')

  setTimeout(() => {
    if (!vlcAttempt || vlcAttempt.closed) {
      try {
        window.open(`xdg-open vlc://${url}`, '_blank')
      } catch (e) {
        copyPlayUrl()
      }
    }
    pause()
  }, 500)
}

const refresh = () => {
  needRefresh.value = false
  if (isHistory.value) {
    loadHistory()
  } else {
    reload(page.value)
  }
}

const save = () => {
  if (playing.value) {
    saveHistory()
  }
}

const saveHistory = () => {
  if (!videoPlayer.value || !dialogVisible.value) {
    return
  }
  const movie = movies.value[0]
  axios.post('/api/history?log=false', {
    cid: 0,
    key: movie.vod_id,
    vodName: movie.vod_name,
    vodPic: movie.vod_pic,
    vodRemarks: playItem.value.title,
    episode: currentVideoIndex.value,
    episodeUrl: playItem.value.url,
    position: Math.round(videoPlayer.value.currentTime * 1000),
    opening: Math.round(skipStart.value * 1000),
    ending: Math.round(skipEnd.value * 1000),
    speed: currentSpeed.value,
    createTime: new Date().getTime()
  }).then()
}

const getHistory = (id: string) => {
  currentVideoIndex.value = 0
  currentTime.value = 0
  currentSpeed.value = 1
  skipStart.value = 0
  skipEnd.value = 0
  minute1.value = 0
  second1.value = 0
  minute2.value = 0
  second2.value = 0

  return axios.get('/history/' + store.token + "?key=" + id).then(({data}) => {
    if (data) {
      if (data.episode > -1) {
        currentVideoIndex.value = data.episode
      } else {
        let path = data.episodeUrl as string
        if (path) {
          currentVideoIndex.value = playlist.value.findIndex(e => {
            let u = e.url
            let index = u.indexOf('?')
            if (index > 0) {
              u = u.substring(0, index)
            }
            index = u.lastIndexOf('/')
            if (index > 0) {
              u = u.substring(index + 1)
            }
            return u === path;
          })
        }
        if (currentVideoIndex.value < 0) {
          currentVideoIndex.value = 0
        }
      }

      currentTime.value = data.position / 1000
      skipStart.value = data.opening > 0 ? data.opening / 1000 : 0
      skipEnd.value = data.ending > 0 ? data.ending / 1000 : 0
      currentSpeed.value = data.speed
      minute1.value = Math.floor(skipStart.value / 60)
      second1.value = skipStart.value % 60
      minute2.value = Math.floor(skipEnd.value / 60)
      second2.value = skipEnd.value % 60
    }
  })
}

const loadHistory = () => {
  axios.get('/api/history?sort=createTime,desc&page=' + (page.value - 1) + '&size=' + size.value).then(({data}) => {
    total.value = data.totalElements
    files.value = data.content.sort((a, b) => b.t - a.t).map(e => {
      return {
        id: e.id,
        vod_id: e.key,
        vod_name: e.vodName,
        vod_pic: e.vodPic,
        vod_remarks: e.vodRemarks,
        index: e.episode + 1,
        progress: formatTime(e.position / 1000),
        vod_tag: 'file',
        vod_time: formatDate(e.createTime)
      }
    })
    isHistory.value = true
    paths.value = [{text: '🏠首页', path: '/'}, {text: '播放记录', path: '/~history'}]
  })
}

const deleteHistory = () => {
  if (batch.value) {
    if (clean.value) {
      clearHistory()
    } else {
      axios.post('/api/history/-/delete', selected.value.map(s => s.id)).then(() => {
        deleteVisible.value = false
        loadHistory()
      })
    }
  } else {
    axios.delete('/api/history/' + history.value.id).then(() => {
      deleteVisible.value = false
      loadHistory()
    })
  }
}

const clearHistory = () => {
  axios.delete('/history/' + store.token).then(() => {
    deleteVisible.value = false
    loadHistory()
  })
}

const handleDeleteBatch = () => {
  batch.value = true
  clean.value = false
  deleteVisible.value = true
}

const handleCleanAll = () => {
  batch.value = true
  clean.value = true
  deleteVisible.value = true
}

const showDelete = (data: VodItem) => {
  history.value = data
  batch.value = false
  clean.value = false
  deleteVisible.value = true
}

const showPush = () => {
  device.value.id = devices.value[0].id
  if (devices.value.length > 1) {
    pushVisible.value = true
  } else {
    doPush()
  }
}

const doPush = () => {
  const url = playItem.value.url
  axios.post(`/api/devices/${device.value.id}/push?type=push&url=${url}`).then(() => {
    ElMessage.success('推送成功')
    pushVisible.value = false
  })
}

const formatDate = (timestamp: number): string => {
  const date: Date = new Date(timestamp);
  const year: number = date.getFullYear();
  const month: string = String(date.getMonth() + 1).padStart(2, '0');
  const day: string = String(date.getDate()).padStart(2, '0');
  const hours: string = String(date.getHours()).padStart(2, '0');
  const minutes: string = String(date.getMinutes()).padStart(2, '0');
  const seconds: string = String(date.getSeconds()).padStart(2, '0');
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`;
}

const formatTime = (seconds: number): string => {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor(seconds % 3600 / 60);
  const s = Math.floor(seconds % 3600 % 60);

  if (h > 0) {
    return [
      h.toString().padStart(2, '0'),
      m.toString().padStart(2, '0'),
      s.toString().padStart(2, '0')
    ].join(':');
  }
  return [
    m.toString().padStart(2, '0'),
    s.toString().padStart(2, '0')
  ].join(':');
}

const replay = () => {
  playItem.value.url = ''
  setTimeout(() => {{
    getPlayUrl()
    startPlay()
  }}, 500)
}

const playNextVideo = () => {
  if (currentVideoIndex.value + 1 == playlist.value.length) {
    return
  }
  currentVideoIndex.value++;
  scroll()
  getPlayUrl()
  startPlay()
}

const playPrevVideo = () => {
  if (currentVideoIndex.value < 1) {
    return
  }
  currentVideoIndex.value--;
  scroll()
  getPlayUrl()
  startPlay()
}

const playVideo = (index: number) => {
  currentVideoIndex.value = index;
  getPlayUrl()
  startPlay()
}

const showNextImage = () => {
  if (currentImageIndex.value + 1 == images.value.length) {
    return
  }
  loadDetail(images.value[currentImageIndex.value + 1].vod_id)
}

const showPrevImage = () => {
  if (currentImageIndex.value < 1) {
    return
  }
  loadDetail(images.value[currentImageIndex.value - 1].vod_id)
}

onMounted(async () => {
  if (!store.token) {
    store.token = await axios.get("/api/token").then(({data}) => {
      return data.token ? data.token.split(",")[0] : "-"
    });
  }

  const link = route.query.link
  if (link) {
    loadShare(link)
  } else {
    const newPath = route.params.path
    filePath.value = newPath ? '/' + newPath.join('/') : '/'
    fetchData()
  }

  if (store.admin) {
    loadDevices()
  }
  currentVolume.value = parseInt(localStorage.getItem('volume') || '100')
  isWideMode.value = localStorage.getItem('wideMode') === 'true'
  timer = setInterval(save, 5000)
  window.addEventListener('keydown', handleKeyDown);
  document.addEventListener('fullscreenchange', handleFullscreenChange);
})

watch([page, size], ([newPage, newSize]) => {
  router.push({
    query: {
      ...route.query,
      page: newPage,
      size: newSize,
    }
  })
})

watch(
  [() => route.query.page, () => route.query.size],
  ([newPage, newSize], [oldPage, oldSize]) => {
    if (newPage !== oldPage || newSize !== oldSize) {
      if (newPage) page.value = parseInt(newPage) || 1
      if (newSize) size.value = parseInt(newSize) || 50
      if (store.token) {
        debouncedFetch()
      }
    }
  },
  {immediate: true}
)

watch(
  () => route.params.path,
  (newPath, oldPath) => {
    const newFilePath = newPath ? '/' + newPath.join('/') : '/'
    const oldFilePath = oldPath ? '/' + oldPath.join('/') : '/'
    if (newFilePath === oldFilePath) {
      return
    }
    if (store.token) {
      filePath.value = newFilePath
      page.value = pageInfo[filePath.value]?.page || 1
      debouncedFetch()
    }
  }
)

onUnmounted(() => {
  clearInterval(timer)
  window.removeEventListener('keydown', handleKeyDown);
  document.removeEventListener('fullscreenchange', handleFullscreenChange);
})
</script>

<style scoped>
video {
  width: 100%;
  height: 1080px;
}

.divider {
  margin: 15px 0;
}

.results {
  width: 100%;
  max-height: 1080px;
  overflow: auto;
}

.players img {
  width: 26px;
  height: 26px;
}

.players a {
  margin: 0 6px;
}

.my-header {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  gap: 16px;
}

#copy:hover {
  cursor: pointer;
}

.wide {
  margin-left: 0;
}
</style>
